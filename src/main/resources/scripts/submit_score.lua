-- Applies one score submission to every leaderboard in a single atomic step, and is idempotent:
-- running it again for the same score entry changes nothing, so the caller can safely retry after a
-- timeout or connection error without double counting.
--
-- KEYS[1]    per-game best scores        (ZSET: username -> best score in that game)
-- KEYS[2]    global leaderboard          (ZSET: username -> sum of best scores across games)
-- KEYS[3]    dedupe marker for this score entry (STRING, expires)
-- KEYS[4..9] period leaderboards, in order: daily, daily+game, weekly, weekly+game, monthly, monthly+game
--            (ZSETs: username -> points earned in the window)
-- ARGV[1]    username
-- ARGV[2]    submitted score (integer)
-- ARGV[3..5] TTL in seconds for the daily / weekly / monthly keys
-- ARGV[6]    pub/sub channel announcing a change
-- ARGV[7]    game name (published so listeners know which board changed)
--
-- Returns { improvement, gameBest, gameRank, globalScore, globalRank }
-- (ranks are 1-based, 0 when the user is not on that board).
local user = ARGV[1]
local score = tonumber(ARGV[2])
local improvement = 0

-- SET ... NX is false when the marker already exists, i.e. this entry was applied before.
local first = redis.call('SET', KEYS[3], '1', 'NX', 'EX', 86400)
if first then
    local current = redis.call('ZSCORE', KEYS[1], user)
    if (not current) or tonumber(current) < score then
        local previous = 0
        if current then
            previous = tonumber(current)
        end
        improvement = score - previous
        redis.call('ZADD', KEYS[1], score, user)
        redis.call('ZINCRBY', KEYS[2], improvement, user)
    end

    for i = 0, 2 do
        local ttl = tonumber(ARGV[3 + i])
        local allKey = KEYS[4 + i * 2]
        local gameKey = KEYS[5 + i * 2]
        redis.call('ZINCRBY', allKey, score, user)
        redis.call('EXPIRE', allKey, ttl)
        redis.call('ZINCRBY', gameKey, score, user)
        redis.call('EXPIRE', gameKey, ttl)
    end

    redis.call('PUBLISH', ARGV[6], ARGV[7])
end

local function rank(key)
    local r = redis.call('ZREVRANK', key, user)
    if r then
        return r + 1
    end
    return 0
end

local function scoreOf(key)
    local s = redis.call('ZSCORE', key, user)
    if s then
        return tonumber(s)
    end
    return 0
end

return { improvement, scoreOf(KEYS[1]), rank(KEYS[1]), scoreOf(KEYS[2]), rank(KEYS[2]) }
