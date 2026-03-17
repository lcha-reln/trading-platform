package com.trading.benchmark;

import com.trading.sbe.*;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.*;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * SBE 编解码性能基准测试。
 *
 * 验证目标：SBE 编解码单条消息 < 50ns。
 *
 * 打包：mvn package -pl benchmark -am -DskipTests -q
 * 运行：java -jar benchmark/target/benchmarks.jar SbeCodecBenchmark
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SbeCodecBenchmark {

    private UnsafeBuffer buffer;
    private MessageHeaderEncoder headerEncoder;
    private NewOrderRequestEncoder orderEncoder;
    private MessageHeaderDecoder headerDecoder;
    private NewOrderRequestDecoder orderDecoder;

    @Setup
    public void setup() {
        // 预分配，只做一次
        buffer       = new UnsafeBuffer(ByteBuffer.allocateDirect(512));
        headerEncoder = new MessageHeaderEncoder();
        orderEncoder  = new NewOrderRequestEncoder();
        headerDecoder = new MessageHeaderDecoder();
        orderDecoder  = new NewOrderRequestDecoder();

        // 预填充一条消息（用于解码基准）
        encode();
    }

    @Benchmark
    public int encode() {
        headerEncoder.wrap(buffer, 0)
                .blockLength(NewOrderRequestEncoder.BLOCK_LENGTH)
                .templateId(NewOrderRequestEncoder.TEMPLATE_ID)
                .schemaId(NewOrderRequestEncoder.SCHEMA_ID)
                .version(NewOrderRequestEncoder.SCHEMA_VERSION);

        orderEncoder.wrap(buffer, MessageHeaderEncoder.ENCODED_LENGTH)
                .correlationId(12345L)
                .accountId(1001L)
                .symbolId(1)
                .side(Side.BUY)
                .orderType(OrderType.LIMIT)
                .timeInForce(TimeInForce.GTC)
                .price(5000000L)
                .quantity(100000L)
                .leverage((short) 1)
                .timestamp(System.nanoTime());

        return orderEncoder.encodedLength();
    }

    @Benchmark
    public long decode() {
        headerDecoder.wrap(buffer, 0);
        orderDecoder.wrap(buffer,
                MessageHeaderDecoder.ENCODED_LENGTH,
                headerDecoder.blockLength(),
                headerDecoder.version());

        return orderDecoder.accountId() + orderDecoder.price() + orderDecoder.quantity();
    }
}
