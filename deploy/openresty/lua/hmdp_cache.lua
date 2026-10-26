-- 边缘缓存公共操作：只有HOT业务快照进入数据字典，协调标记单独存放。
local json = require "cjson.safe"
local redis = require "resty.redis"
local resty_lock = require "resty.lock"
local M = {}
local data = ngx.shared.shop_cache
local control = ngx.shared.refresh_control
local max_shops = tonumber(os.getenv("HMDP_HOT_MAX_SHOPS")) or 500
local max_bytes = tonumber(os.getenv("HMDP_HOT_MAX_ENTRY_BYTES")) or 8192
local local_ms = (tonumber(os.getenv("HMDP_EDGE_LOGICAL_TTL_SECONDS")) or 5) * 1000
local read_script = [[
local mode=redis.call('HGET',KEYS[1],'mode'); if not mode then return {} end
return {mode,redis.call('HGET',KEYS[1],'revision'),redis.call('HGET',KEYS[1],'deleted'),
redis.call('GET',mode=='HOT' and KEYS[3] or KEYS[2]) or ''}
]]
local function compare(a, b)
    if not b then
        return 1
    end
    if #a ~= #b then
        return #a > #b and 1 or -1
    end
    if a == b then
        return 0
    end
    return a > b and 1 or -1
end
M.compare = compare

local function critical(fn, wait)
    -- 只覆盖非yield的字典比较/修改；不在这个短临界区中访问网络。
    local lock, err = resty_lock:new("refresh_control", { timeout = wait or 0.05, exptime = 5 })
    if not lock then
        return nil, err
    end
    local elapsed;
    elapsed, err = lock:lock("cache-write")
    if not elapsed then
        return nil, err
    end
    local ok, result, why = pcall(fn)
    lock:unlock()
    if not ok then
        return nil, result
    end
    return result, why
end

function M.normalize_id(id)
    id = id:gsub("^0+", "")
    if id == "" or #id > 19 or (#id == 19 and id > "9223372036854775807") then
        return nil
    end
    return id
end

function M.peek(id)
    local raw = data:get(id)
    if not raw then
        return nil
    end
    local entry = json.decode(raw)
    if type(entry) ~= "table" or not entry.snapshot or not entry.response then
        return nil
    end
    return entry
end

function M.read_redis(id)
    local client = redis:new()
    client:set_timeout(200)
    local ok, err = client:connect(os.getenv("HMDP_REDIS_HOST") or "127.0.0.1",
            tonumber(os.getenv("HMDP_REDIS_PORT")) or 6379)
    if not ok then
        return nil, err
    end
    local password = os.getenv("HMDP_REDIS_PASSWORD")
    if password and password ~= "" then
        ok, err = client:auth(password)
    end
    if ok then
        ok, err = client:select(tonumber(os.getenv("HMDP_REDIS_DB")) or 0)
    end
    local values
    if ok then
        values, err = client:eval(read_script, 3, "cache:shop:v3:meta:" .. id,
                "cache:shop:v3:normal:" .. id, "cache:shop:v3:hot:" .. id)
    end
    if values then
        client:set_keepalive(10000, 100)
    else
        client:close()
    end
    if not values or #values == 0 then
        return nil, err or "route missing"
    end
    if type(values[2]) ~= "string" then
        return nil, "invalid route"
    end
    local snapshot = values[4] ~= "" and json.decode(values[4]) or nil
    if type(snapshot) ~= "table" or snapshot.id ~= id or snapshot.mode ~= values[1] then
        snapshot = nil
    end
    return { mode = values[1], revision = values[2], deleted = values[3] == "1", snapshot = snapshot }
end

function M.response(snapshot)
    if snapshot.missing then
        return '{"success":false,"errorMsg":"商铺不存在"}'
    end
    -- 商铺JSON保持Jackson原输出，不用cjson解码/再编码大整数ID及日期。
    return '{"success":true,"data":' .. snapshot.dataJson .. '}'
end

function M.view(snapshot)
    return { snapshot = snapshot, localLogicalExpireAtEpochMillis = math.min(ngx.now() * 1000 + local_ms, snapshot.logicalExpireAtEpochMillis or 0) }
end

function M.put(id, view, token)
    local value = view.snapshot
    if not value or value.mode ~= "HOT" or value.missing then
        return false, "not HOT"
    end
    return critical(function()
        if token and control:get("flight:" .. id) ~= token then
            return false, "refresh lease lost"
        end
        local required = control:get("revision:" .. id)
        if required and compare(value.revision, required) < 0 then
            return false, "old revision"
        end
        local old_raw = data:get(id)
        local old = old_raw and json.decode(old_raw) or nil
        if old then
            local cmp = compare(value.revision, old.snapshot.revision)
            if cmp < 0 or (cmp == 0 and value.logicalExpireAtEpochMillis < old.snapshot.logicalExpireAtEpochMillis)
                    or (cmp == 0 and value.logicalExpireAtEpochMillis == old.snapshot.logicalExpireAtEpochMillis
                    and view.localLogicalExpireAtEpochMillis < old.localLogicalExpireAtEpochMillis) then
                return false, "older publication"
            end
        else
            if (control:get("resident") or 0) >= max_shops then
                return false, "HOT list capacity exceeded"
            end
        end
        -- 共享字典只存响应与必要元信息，不重复保存dataJson，降低每条实际占用。
        local entry = { snapshot = { id = value.id, mode = value.mode, revision = value.revision,
                                     logicalExpireAtEpochMillis = value.logicalExpireAtEpochMillis, missing = false },
                        response = M.response(value), localLogicalExpireAtEpochMillis = view.localLogicalExpireAtEpochMillis }
        local encoded = json.encode(entry)
        if not encoded or #encoded > max_bytes then
            return false, "HOT entry exceeds byte budget"
        end
        -- safe_set容量不足时拒绝分配，不强制淘汰其他驻留热点；0是没有物理TTL。
        local ok, err = data:safe_set(id, encoded, 0)
        if not ok then
            return false, err
        end
        if not old_raw then
            control:incr("resident", 1, 0)
        end
        return true
    end)
end

function M.remove(id, revision)
    return critical(function()
        local raw = data:get(id)
        local old = raw and json.decode(raw) or nil
        -- 控制水位有短租约；即使它已过期，迟到删除也不能移除版本更高的正文。
        if revision and old and compare(revision, old.snapshot.revision) < 0 then
            return true
        end
        local required = control:get("revision:" .. id)
        if revision and (not required or compare(revision, required) >= 0) then
            control:set("revision:" .. id, revision, 60) -- 退役元信息租约，不是热点正文TTL。
        elseif revision then
            return true
        end
        if data:get(id) then
            data:delete(id);
            control:incr("resident", -1, 0)
        end
        return true
    end)
end

function M.http_get(path)
    local client = ngx.socket.tcp()
    client:settimeout(2500)
    local ok, err = client:connect(os.getenv("HMDP_JAVA_HOST") or "127.0.0.1",
            tonumber(os.getenv("HMDP_JAVA_PORT")) or 8081)
    if not ok then
        return nil, 503, err
    end
    local key = os.getenv("HMDP_CACHE_INTERNAL_KEY")
    if not key or key == "" then
        client:close();
        return nil, 503, "internal key missing"
    end
    ok, err = client:send("GET " .. path .. " HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n"
            .. "X-HMDP-Internal-Key: " .. key .. "\r\n\r\n")
    if not ok then
        client:close();
        return nil, 503, err
    end
    local status_line = client:receive("*l")
    local status = status_line and tonumber(status_line:match("^HTTP/%d%.%d (%d+)")) or 503
    local length
    while true do
        local line = client:receive("*l")
        if not line then
            client:close();
            return nil, 503, "incomplete headers"
        end
        line = line:gsub("\r$", "")
        if line == "" then
            break
        end
        local size = line:match("^[Cc]ontent%-[Ll]ength:%s*(%d+)")
        if size then
            length = tonumber(size)
        end
    end
    if length and length > 4194304 then
        client:close();
        return nil, 503, "response too large"
    end
    local body
    if length then
        body, err = client:receive(length)
    else
        body, err = client:receive("*a")
    end
    client:close()
    if not body then
        return nil, 503, err
    end
    return json.decode(body), status
end

local function release(id, token)
    critical(function()
        if control:get("flight:" .. id) == token then
            control:delete("flight:" .. id)
        end ;
        return true
    end)
end

local function refresh(premature, id, token)
    if premature then
        release(id, token);
        return
    end
    local ok, err = xpcall(function()
        if control:get("flight:" .. id) ~= token then
            return
        end
        local old = M.peek(id)
        if old and old.localLogicalExpireAtEpochMillis > ngx.now() * 1000 then
            return
        end -- 锁内二次检查。
        local route = M.read_redis(id)
        if route and (route.mode ~= "HOT" or route.deleted) then
            M.remove(id, route.revision);
            return
        end
        local view
        if route and route.snapshot and compare(route.snapshot.revision, route.revision) >= 0
                and route.snapshot.logicalExpireAtEpochMillis > ngx.now() * 1000 then
            view = M.view(route.snapshot) -- 上层5秒刷新只读Redis，不直接查MySQL。
        else
            local status;
            view, status = M.http_get("/internal/shop-cache/" .. id .. "?fresh=true")
            if status ~= 200 or not view or not view.snapshot then
                error("source refresh failed: " .. tostring(status))
            end
        end
        if view.snapshot.mode ~= "HOT" or view.snapshot.missing then
            M.remove(id, view.snapshot.revision);
            return
        end
        local stored, why = M.put(id, view, token)
        if not stored and why ~= "older publication" and why ~= "old revision" then
            error(why)
        end
    end, debug.traceback)
    if not ok then
        control:set("retry:" .. id, true, 0.5)
        ngx.log(ngx.WARN, "SHOP_EDGE_REFRESH_FAILED id=", id, " ", err)
    end
    release(id, token)
end

function M.schedule(id)
    if control:get("retry:" .. id) then
        return true
    end
    local token = tostring(ngx.worker.pid()) .. ":" .. tostring(ngx.now()) .. ":" .. tostring(math.random())
    local acquired, why = critical(function()
        return control:add("flight:" .. id, token, 5)
    end, 0)
    if not acquired then
        return why == "exists", why
    end
    local ok, err = ngx.timer.at(0, refresh, id, token) -- 一次性请求触发回调，不是周期扫描。
    if not ok then
        release(id, token);
        return false, err
    end
    return true
end

local function release_normal(id, token)
    critical(function()
        if control:get("normal-flight:" .. id) == token then
            control:delete("normal-flight:" .. id)
        end
        return true
    end)
end

local function refresh_normal(premature, id, token)
    if premature then
        release_normal(id, token);
        return
    end
    local ok, err = xpcall(function()
        if control:get("normal-flight:" .. id) ~= token then
            return
        end
        local route = M.read_redis(id)
        local snapshot = route and route.snapshot or nil
        -- 获标记后二次检查：普通源已更新就结束，避免多个迟到timer重复回源。
        if route and route.mode == "NORMAL" and snapshot
                and snapshot.missing == route.deleted and compare(snapshot.revision, route.revision) >= 0 then
            return
        end
        if control:get("normal-flight:" .. id) ~= token then
            return
        end
        local view, status = M.http_get("/internal/shop-cache/" .. id .. "?fresh=true")
        if status ~= 200 or not view or not view.snapshot then
            error("normal source refresh failed: " .. tostring(status))
        end
        -- 普通刷新只确保Java完成Redis发布，不调用M.put，不保存普通响应，也不修改正文TTL。
    end, debug.traceback)
    if not ok then
        control:set("normal-retry:" .. id, true, 0.5)
        ngx.log(ngx.WARN, "SHOP_NORMAL_EDGE_REFRESH_FAILED id=", id, " ", err)
    end
    release_normal(id, token)
end

function M.schedule_normal(id)
    if control:get("normal-retry:" .. id) then
        return true
    end
    local token = tostring(ngx.worker.pid()) .. ":" .. tostring(ngx.now()) .. ":" .. tostring(math.random())
    local acquired, why = critical(function()
        return control:add("normal-flight:" .. id, token, 5)
    end, 0)
    if not acquired then
        return why == "exists", why
    end
    local ok, err = ngx.timer.at(0, refresh_normal, id, token)
    if not ok then
        release_normal(id, token)
        control:set("normal-retry:" .. id, true, 0.1)
        return false, err
    end
    return true
end

function M.notify(id)
    local route, err = M.read_redis(id)
    if not route then
        return false, err
    end
    if route.mode ~= "HOT" or route.deleted then
        return M.remove(id, route.revision)
    end
    local marked, why = critical(function()
        local required = control:get("revision:" .. id)
        if required and compare(route.revision, required) < 0 then
            return true
        end
        control:set("revision:" .. id, route.revision, 60) -- 控制版本只前进，迟到通知不能降低水位。
        local raw = data:get(id)
        if raw then
            local value = json.decode(raw)
            if value and compare(value.snapshot.revision, route.revision) <= 0 then
                value.localLogicalExpireAtEpochMillis = 0
                local ok, e = data:safe_set(id, json.encode(value), 0)
                if not ok then
                    return false, e
                end
            end
        end
        return true
    end)
    if not marked then
        return false, why
    end
    return M.schedule(id)
end

function M.warm_all(premature)
    if premature then
        return
    end
    local ids, status = M.http_get("/internal/shop-cache/hot")
    if status ~= 200 or type(ids) ~= "table" then
        ngx.log(ngx.ERR, "SHOP_EDGE_BOOTSTRAP_FAILED");
        return
    end
    for _, id in ipairs(ids) do
        local view, code = M.http_get("/internal/shop-cache/" .. id .. "?fresh=true")
        if code == 200 and view and view.snapshot then
            local ok, err = M.put(id, view, nil)
            if not ok then
                ngx.log(ngx.ERR, "SHOP_EDGE_WARM_FAILED id=", id, " ", err)
            end
        else
            ngx.log(ngx.ERR, "SHOP_EDGE_WARM_FAILED id=", id)
        end
    end
    ngx.log(ngx.INFO, "SHOP_EDGE_BOOTSTRAP_READY count=", control:get("resident") or 0)
end

function M.stats()
    return { entries = control:get("resident") or 0, ids = data:get_keys(0),
             capacity = data:capacity(), freeSpace = data:free_space() }
end
return M
