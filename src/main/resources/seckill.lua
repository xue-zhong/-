--参数优惠劵id
local voucherId=ARGV[1]
--参数用户id
local userId=ARGV[2]
--参数订单id
local orderId=ARGV[3]
--库存key
local stockKey="seckill:stock:" .. voucherId
--订单key
local orderKey="seckill:order:" .. voucherId
--判断库存是否充足
if(tonumber(redis.call('get',stockKey))<=0)then
    return 1
end
--判断用户是否下单
if(redis.call('sismember',orderKey,userId)==1)then
    return 2
end
--扣除库存
redis.call('incrby',stockKey,-1)
--把用户id存入订单
redis.call('sadd',orderKey,userId)
--发消息给消息队列
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0