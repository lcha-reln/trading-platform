package com.trading.benchmark;

import com.trading.util.ObjectPool;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * ObjectPool vs new 对象分配对比基准测试。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ObjectPoolBenchmark {
    private ObjectPool<TestObject> pool;

    @Setup
    public void setup() {
        pool = new ObjectPool<>(TestObject::new, 1024);
    }

    @Benchmark
    public long withPool() {
        final TestObject obj = pool.borrow();
        obj.reset(System.nanoTime(), 42L);
        final long result = obj.field1 + obj.field2;
        pool.release(obj);
        return result;
    }

    @Benchmark
    public long withNew() {
        final TestObject obj = new TestObject();  // 触发 GC 压力
        obj.reset(System.nanoTime(), 42L);
        return obj.field1 + obj.field2;
    }

    static class TestObject {
        long field1, field2, field3, field4;

        void reset(long a, long b) {
            this.field1 = a;
            this.field2 = b;
        }
    }
}
