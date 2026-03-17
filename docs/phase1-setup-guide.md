# Phase 1 搭建教程：基础框架

> **目标：** 搭建 Maven 多模块项目骨架，定义 SBE 消息 Schema，实现公共工具类，
> 并验证 Aeron IPC 和 Disruptor 基础链路，达到单链路延迟 < 1μs。
>
> **环境：** macOS (aarch64) / Linux · Java 17+ · Maven 3.9+  
> **预计耗时：** 2 周（含调试和压测）

---

## 目录

1. [环境准备](#1-环境准备)
2. [创建 Maven 多模块项目](#2-创建-maven-多模块项目)
3. [定义 SBE 消息 Schema](#3-定义-sbe-消息-schema)
4. [实现 common-util 工具库](#4-实现-common-util-工具库)
5. [实现 Aeron IPC 基础链路 Demo](#5-实现-aeron-ipc-基础链路-demo)
6. [实现 Disruptor Pipeline Demo](#6-实现-disruptor-pipeline-demo)
7. [完整链路验证：Aeron IPC + Disruptor](#7-完整链路验证aeron-ipc--disruptor)
8. [延迟基准测试](#8-延迟基准测试)
9. [常见问题排查](#9-常见问题排查)

---

## 1. 环境准备

### 1.1 安装 Java 21

Phase 1 推荐使用 Java 21（LTS，支持 Virtual Threads 和更好的 ZGC）。

**macOS（使用 SDKMAN）：**

```bash
# 安装 SDKMAN
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# 安装 Java 21
sdk install java 21.0.3-tem

# 设置为默认
sdk use java 21.0.3-tem

# 验证
java -version
# 期望输出：openjdk version "21.0.3" ...
```

**macOS（使用 Homebrew）：**

```bash
brew install openjdk@21
echo 'export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
java -version
```

### 1.2 确认 Maven 版本

```bash
mvn -version
# 期望：Apache Maven 3.9.x
```

如需安装：

```bash
# macOS
brew install maven

# 验证
mvn -version
```

### 1.3 配置 /dev/shm（macOS 特别说明）

Aeron IPC 默认使用 `/dev/shm`（Linux 共享内存），macOS 没有此目录，需手动指定。

**macOS 替代方案：** 使用 `/tmp/aeron` 目录（Phase 1 开发阶段够用，生产环境用 Linux）

```bash
mkdir -p /tmp/aeron
# 后续代码中统一通过 System Property 指定：
# -Daeron.dir=/tmp/aeron
```

### 1.4 安装 IDE 插件（推荐 IntelliJ IDEA）

- **SBE Plugin**：搜索 "Simple Binary Encoding" 安装（提供 .xml schema 高亮）
- **Maven Helper**：可视化依赖树

---

## 2. 创建 Maven 多模块项目

### 2.1 创建根目录结构

```bash
mkdir -p trading-platform
cd trading-platform

# 创建所有子模块目录
mkdir -p common/common-sbe/src/main/resources/sbe
mkdir -p common/common-sbe/src/main/java/com/trading/sbe
mkdir -p common/common-model/src/main/java/com/trading/model
mkdir -p common/common-util/src/main/java/com/trading/util
mkdir -p common/common-util/src/test/java/com/trading/util
mkdir -p gateway-service/src/main/java/com/trading/gateway
mkdir -p counter-service/src/main/java/com/trading/counter
mkdir -p matching-engine/src/main/java/com/trading/matching
mkdir -p matching-engine/src/test/java/com/trading/matching
mkdir -p push-service/src/main/java/com/trading/push
mkdir -p journal-service/src/main/java/com/trading/journal
mkdir -p benchmark/src/main/java/com/trading/benchmark
```

### 2.2 根 POM（trading-platform/pom.xml）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.trading</groupId>
    <artifactId>trading-platform</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>High Performance Trading Platform</name>

    <modules>
        <module>common/common-sbe</module>
        <module>common/common-model</module>
        <module>common/common-util</module>
        <module>matching-engine</module>
        <module>counter-service</module>
        <module>gateway-service</module>
        <module>push-service</module>
        <module>journal-service</module>
        <module>benchmark</module>
    </modules>

    <properties>
        <java.version>21</java.version>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

        <!-- 核心依赖版本 -->
        <aeron.version>1.44.1</aeron.version>
        <disruptor.version>4.0.0</disruptor.version>
        <agrona.version>1.21.2</agrona.version>
        <sbe.version>1.30.0</sbe.version>
        <netty.version>4.1.108.Final</netty.version>
        <affinity.version>3.23.3</affinity.version>
        <hdrhistogram.version>2.2.2</hdrhistogram.version>
        <slf4j.version>2.0.12</slf4j.version>
        <logback.version>1.5.3</logback.version>
        <junit.version>5.10.2</junit.version>
        <jmh.version>1.37</jmh.version>
    </properties>

    <!-- 统一管理所有依赖版本，子模块直接引用无需写版本 -->
    <dependencyManagement>
        <dependencies>
            <!-- 内部模块 -->
            <dependency>
                <groupId>com.trading</groupId>
                <artifactId>common-sbe</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.trading</groupId>
                <artifactId>common-model</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.trading</groupId>
                <artifactId>common-util</artifactId>
                <version>${project.version}</version>
            </dependency>

            <!-- Aeron (含 Cluster、Archive) -->
            <dependency>
                <groupId>io.aeron</groupId>
                <artifactId>aeron-all</artifactId>
                <version>${aeron.version}</version>
            </dependency>

            <!-- LMAX Disruptor -->
            <dependency>
                <groupId>com.lmax</groupId>
                <artifactId>disruptor</artifactId>
                <version>${disruptor.version}</version>
            </dependency>

            <!-- Agrona 无锁数据结构 -->
            <dependency>
                <groupId>org.agrona</groupId>
                <artifactId>agrona</artifactId>
                <version>${agrona.version}</version>
            </dependency>

            <!-- SBE -->
            <dependency>
                <groupId>uk.co.real-logic</groupId>
                <artifactId>sbe-all</artifactId>
                <version>${sbe.version}</version>
            </dependency>

            <!-- Netty -->
            <dependency>
                <groupId>io.netty</groupId>
                <artifactId>netty-all</artifactId>
                <version>${netty.version}</version>
            </dependency>

            <!-- CPU 亲和性绑定 -->
            <dependency>
                <groupId>net.openhft</groupId>
                <artifactId>Java-Thread-Affinity</artifactId>
                <version>${affinity.version}</version>
            </dependency>

            <!-- 延迟直方图 -->
            <dependency>
                <groupId>org.hdrhistogram</groupId>
                <artifactId>HdrHistogram</artifactId>
                <version>${hdrhistogram.version}</version>
            </dependency>

            <!-- 日志 -->
            <dependency>
                <groupId>org.slf4j</groupId>
                <artifactId>slf4j-api</artifactId>
                <version>${slf4j.version}</version>
            </dependency>
            <dependency>
                <groupId>ch.qos.logback</groupId>
                <artifactId>logback-classic</artifactId>
                <version>${logback.version}</version>
            </dependency>

            <!-- 测试 -->
            <dependency>
                <groupId>org.junit.jupiter</groupId>
                <artifactId>junit-jupiter</artifactId>
                <version>${junit.version}</version>
                <scope>test</scope>
            </dependency>

            <!-- JMH 微基准 -->
            <dependency>
                <groupId>org.openjdk.jmh</groupId>
                <artifactId>jmh-core</artifactId>
                <version>${jmh.version}</version>
            </dependency>
            <dependency>
                <groupId>org.openjdk.jmh</groupId>
                <artifactId>jmh-generator-annprocess</artifactId>
                <version>${jmh.version}</version>
                <scope>provided</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.13.0</version>
                    <configuration>
                        <source>21</source>
                        <target>21</target>
                        <compilerArgs>
                            <arg>--add-opens=java.base/sun.nio.ch=ALL-UNNAMED</arg>
                        </compilerArgs>
                    </configuration>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.2.5</version>
                    <configuration>
                        <argLine>
                            --add-opens java.base/sun.nio.ch=ALL-UNNAMED
                            --add-opens java.base/java.lang=ALL-UNNAMED
                        </argLine>
                    </configuration>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

### 2.3 common-sbe POM（common/common-sbe/pom.xml）

这个模块负责将 SBE XML Schema 编译生成 Java 编解码器。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.trading</groupId>
        <artifactId>trading-platform</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>common-sbe</artifactId>
    <name>Common SBE Codec</name>

    <dependencies>
        <dependency>
            <groupId>uk.co.real-logic</groupId>
            <artifactId>sbe-all</artifactId>
        </dependency>
        <dependency>
            <groupId>org.agrona</groupId>
            <artifactId>agrona</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- SBE 代码生成插件：将 XML Schema 生成 Java 编解码器 -->
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.2.0</version>
                <executions>
                    <execution>
                        <id>generate-sbe-codecs</id>
                        <phase>generate-sources</phase>
                        <goals>
                            <goal>java</goal>
                        </goals>
                        <configuration>
                            <mainClass>uk.co.real_logic.sbe.SbeTool</mainClass>
                            <systemProperties>
                                <!-- 生成代码的目标包名 -->
                                <systemProperty>
                                    <key>sbe.output.dir</key>
                                    <value>${project.build.directory}/generated-sources/sbe</value>
                                </systemProperty>
                                <systemProperty>
                                    <key>sbe.target.language</key>
                                    <value>Java</value>
                                </systemProperty>
                                <systemProperty>
                                    <key>sbe.validation.stop.on.error</key>
                                    <value>true</value>
                                </systemProperty>
                                <systemProperty>
                                    <key>sbe.java.generate.interfaces</key>
                                    <value>true</value>
                                </systemProperty>
                            </systemProperties>
                            <!-- 所有 Schema 文件 -->
                            <arguments>
                                <argument>${project.basedir}/src/main/resources/sbe/TradingMessages.xml</argument>
                            </arguments>
                        </configuration>
                    </execution>
                </executions>
            </plugin>

            <!-- 将生成的代码目录加入编译路径 -->
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>build-helper-maven-plugin</artifactId>
                <version>3.5.0</version>
                <executions>
                    <execution>
                        <id>add-generated-sources</id>
                        <phase>generate-sources</phase>
                        <goals>
                            <goal>add-source</goal>
                        </goals>
                        <configuration>
                            <sources>
                                <source>${project.build.directory}/generated-sources/sbe</source>
                            </sources>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

### 2.4 common-model POM（common/common-model/pom.xml）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.trading</groupId>
        <artifactId>trading-platform</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>common-model</artifactId>
    <name>Common Domain Model</name>

    <dependencies>
        <dependency>
            <groupId>org.agrona</groupId>
            <artifactId>agrona</artifactId>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
    </dependencies>
</project>
```

### 2.5 common-util POM（common/common-util/pom.xml）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.trading</groupId>
        <artifactId>trading-platform</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>common-util</artifactId>
    <name>Common Utilities</name>

    <dependencies>
        <dependency>
            <groupId>org.agrona</groupId>
            <artifactId>agrona</artifactId>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 2.6 matching-engine POM（matching-engine/pom.xml）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.trading</groupId>
        <artifactId>trading-platform</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>matching-engine</artifactId>
    <name>Matching Engine</name>

    <dependencies>
        <dependency>
            <groupId>com.trading</groupId>
            <artifactId>common-sbe</artifactId>
        </dependency>
        <dependency>
            <groupId>com.trading</groupId>
            <artifactId>common-model</artifactId>
        </dependency>
        <dependency>
            <groupId>com.trading</groupId>
            <artifactId>common-util</artifactId>
        </dependency>
        <dependency>
            <groupId>io.aeron</groupId>
            <artifactId>aeron-all</artifactId>
        </dependency>
        <dependency>
            <groupId>com.lmax</groupId>
            <artifactId>disruptor</artifactId>
        </dependency>
        <dependency>
            <groupId>org.agrona</groupId>
            <artifactId>agrona</artifactId>
        </dependency>
        <dependency>
            <groupId>net.openhft</groupId>
            <artifactId>Java-Thread-Affinity</artifactId>
        </dependency>
        <dependency>
            <groupId>org.hdrhistogram</groupId>
            <artifactId>HdrHistogram</artifactId>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 2.7 benchmark POM（benchmark/pom.xml）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.trading</groupId>
        <artifactId>trading-platform</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>benchmark</artifactId>
    <name>Benchmark</name>

    <dependencies>
        <dependency>
            <groupId>com.trading</groupId>
            <artifactId>common-util</artifactId>
        </dependency>
        <dependency>
            <groupId>com.trading</groupId>
            <artifactId>matching-engine</artifactId>
        </dependency>
        <dependency>
            <groupId>io.aeron</groupId>
            <artifactId>aeron-all</artifactId>
        </dependency>
        <dependency>
            <groupId>com.lmax</groupId>
            <artifactId>disruptor</artifactId>
        </dependency>
        <dependency>
            <groupId>org.agrona</groupId>
            <artifactId>agrona</artifactId>
        </dependency>
        <dependency>
            <groupId>org.hdrhistogram</groupId>
            <artifactId>HdrHistogram</artifactId>
        </dependency>
        <dependency>
            <groupId>org.openjdk.jmh</groupId>
            <artifactId>jmh-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.openjdk.jmh</groupId>
            <artifactId>jmh-generator-annprocess</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- 打包为可执行 fat-jar，方便独立运行基准测试 -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.5.2</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <finalName>benchmarks</finalName>
                            <transformers>
                                <transformer implementation=
                                    "org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>org.openjdk.jmh.Main</mainClass>
                                </transformer>
                            </transformers>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

### 2.8 验证项目骨架

```bash
cd trading-platform
mvn validate -q
# 期望：BUILD SUCCESS，无报错
```

---

## 3. 定义 SBE 消息 Schema

### 3.1 SBE 核心概念

SBE（Simple Binary Encoding）是一种**固定长度二进制编解码**规范：

- 消息按字段顺序顺序读写，无需查找字段偏移量
- 所有数值类型直接映射到底层 DirectBuffer，**零拷贝**
- 生成的编解码器是**飞行记录器**风格：encoder/decoder 包装同一块内存，不创建中间对象

### 3.2 SBE Schema 文件

创建文件：`common/common-sbe/src/main/resources/sbe/TradingMessages.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<messageSchema package="com.trading.sbe"
               id="1"
               version="0"
               semanticVersion="0.1"
               description="Trading Platform Messages"
               byteOrder="littleEndian"
               xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
               xsi:noNamespaceSchemaLocation="message-schema.xsd">

    <!-- ============================================================
         基础类型定义
         ============================================================ -->
    <types>

        <!-- 消息头：每条消息都以此开头，SBE 标准格式 -->
        <composite name="messageHeader" description="SBE Message Header">
            <type name="blockLength"  primitiveType="uint16"/>
            <type name="templateId"   primitiveType="uint16"/>
            <type name="schemaId"     primitiveType="uint16"/>
            <type name="version"      primitiveType="uint16"/>
        </composite>

        <!-- 分组头（用于变长数组，Phase 1 暂不使用） -->
        <composite name="groupSizeEncoding">
            <type name="blockLength" primitiveType="uint16"/>
            <type name="numInGroup"  primitiveType="uint16"/>
        </composite>

        <!-- 变长字符串头 -->
        <composite name="varStringEncoding">
            <type name="length"      primitiveType="uint32"/>
            <type name="varData"     primitiveType="uint8" length="0" characterEncoding="UTF-8"/>
        </composite>

        <!-- ---- 枚举类型 ---- -->

        <!-- 买卖方向 -->
        <enum name="Side" encodingType="int8">
            <validValue name="BUY">1</validValue>
            <validValue name="SELL">2</validValue>
        </enum>

        <!-- 订单类型 -->
        <enum name="OrderType" encodingType="int8">
            <validValue name="LIMIT">1</validValue>
            <validValue name="MARKET">2</validValue>
            <validValue name="IOC">3</validValue>
            <validValue name="FOK">4</validValue>
            <validValue name="POST_ONLY">5</validValue>
        </enum>

        <!-- 订单有效时间 -->
        <enum name="TimeInForce" encodingType="int8">
            <validValue name="GTC">1</validValue>  <!-- Good Till Cancel -->
            <validValue name="GTD">2</validValue>  <!-- Good Till Date  -->
            <validValue name="GFD">3</validValue>  <!-- Good For Day    -->
        </enum>

        <!-- 订单状态 -->
        <enum name="OrderStatus" encodingType="int8">
            <validValue name="NEW">0</validValue>
            <validValue name="PARTIALLY_FILLED">1</validValue>
            <validValue name="FILLED">2</validValue>
            <validValue name="CANCELLED">3</validValue>
            <validValue name="REJECTED">4</validValue>
            <validValue name="PENDING">5</validValue>
        </enum>

        <!-- 执行类型（回报事件类型） -->
        <enum name="ExecType" encodingType="int8">
            <validValue name="NEW">0</validValue>           <!-- 新订单确认 -->
            <validValue name="PARTIAL_FILL">1</validValue>  <!-- 部分成交 -->
            <validValue name="FILL">2</validValue>          <!-- 全部成交 -->
            <validValue name="CANCELLED">3</validValue>     <!-- 撤单确认 -->
            <validValue name="REJECTED">4</validValue>      <!-- 拒绝 -->
            <validValue name="EXPIRED">5</validValue>       <!-- 过期 -->
        </enum>

        <!-- 拒绝原因码 -->
        <enum name="RejectReason" encodingType="int8">
            <validValue name="NONE">0</validValue>
            <validValue name="INSUFFICIENT_BALANCE">1</validValue>
            <validValue name="INVALID_PRICE">2</validValue>
            <validValue name="INVALID_QUANTITY">3</validValue>
            <validValue name="SYMBOL_NOT_FOUND">4</validValue>
            <validValue name="SYMBOL_HALTED">5</validValue>
            <validValue name="EXCEED_POSITION_LIMIT">6</validValue>
            <validValue name="PRICE_OUT_OF_BAND">7</validValue>
            <validValue name="ORDER_NOT_FOUND">8</validValue>
            <validValue name="RATE_LIMIT_EXCEEDED">9</validValue>
            <validValue name="SYSTEM_BUSY">10</validValue>
        </enum>

        <!-- 交易对类型 -->
        <enum name="SymbolType" encodingType="int8">
            <validValue name="SPOT">1</validValue>
            <validValue name="PERP">2</validValue>
            <validValue name="FUTURES">3</validValue>
        </enum>

    </types>

    <!-- ============================================================
         消息定义
         templateId 规划：
           1-99:   客户端入站消息
           100-199: 出站回报消息
           200-299: 内部消息（Counter → MatchEngine）
           300-399: 撮合回报（MatchEngine 输出）
         ============================================================ -->

    <!-- ==================== 客户端入站消息 ==================== -->

    <!-- 新建订单请求 -->
    <message name="NewOrderRequest" id="1" description="Place a new order">
        <field name="correlationId"  id="1"  type="int64"       description="客户端唯一请求ID，用于匹配回报"/>
        <field name="accountId"      id="2"  type="int64"       description="账户ID"/>
        <field name="symbolId"       id="3"  type="int32"       description="交易对ID"/>
        <field name="side"           id="4"  type="Side"        description="买卖方向"/>
        <field name="orderType"      id="5"  type="OrderType"   description="订单类型"/>
        <field name="timeInForce"    id="6"  type="TimeInForce" description="有效时间"/>
        <field name="price"          id="7"  type="int64"       description="委托价格（固定精度整数，Market单填0）"/>
        <field name="quantity"       id="8"  type="int64"       description="委托数量（固定精度整数）"/>
        <field name="leverage"       id="9"  type="int16"       description="杠杆倍数（Spot填1）"/>
        <field name="timestamp"      id="10" type="int64"       description="客户端时间戳（纳秒 UTC epoch）"/>
    </message>

    <!-- 撤销订单请求 -->
    <message name="CancelOrderRequest" id="2" description="Cancel an existing order">
        <field name="correlationId"  id="1"  type="int64"  description="客户端唯一请求ID"/>
        <field name="accountId"      id="2"  type="int64"  description="账户ID"/>
        <field name="orderId"        id="3"  type="int64"  description="要撤销的订单ID"/>
        <field name="symbolId"       id="4"  type="int32"  description="交易对ID"/>
        <field name="timestamp"      id="5"  type="int64"  description="客户端时间戳（纳秒）"/>
    </message>

    <!-- ==================== 出站回报消息 ==================== -->

    <!-- 订单执行回报（订单状态变更的核心消息） -->
    <message name="ExecutionReport" id="101" description="Order execution report">
        <field name="orderId"        id="1"  type="int64"       description="系统订单ID（全局唯一）"/>
        <field name="correlationId"  id="2"  type="int64"       description="对应请求的 correlationId"/>
        <field name="accountId"      id="3"  type="int64"       description="账户ID"/>
        <field name="symbolId"       id="4"  type="int32"       description="交易对ID"/>
        <field name="execType"       id="5"  type="ExecType"    description="本次执行事件类型"/>
        <field name="orderStatus"    id="6"  type="OrderStatus" description="订单当前状态"/>
        <field name="side"           id="7"  type="Side"        description="买卖方向"/>
        <field name="price"          id="8"  type="int64"       description="委托价格"/>
        <field name="quantity"       id="9"  type="int64"       description="委托数量"/>
        <field name="filledQty"      id="10" type="int64"       description="累计成交数量"/>
        <field name="leavesQty"      id="11" type="int64"       description="剩余未成交数量"/>
        <field name="lastFillPrice"  id="12" type="int64"       description="本次成交价格（无成交填0）"/>
        <field name="lastFillQty"    id="13" type="int64"       description="本次成交数量（无成交填0）"/>
        <field name="fee"            id="14" type="int64"       description="本次手续费（固定精度）"/>
        <field name="rejectReason"   id="15" type="RejectReason" description="拒绝原因（非拒绝填NONE）"/>
        <field name="timestamp"      id="16" type="int64"       description="系统时间戳（纳秒）"/>
    </message>

    <!-- ==================== 内部消息（Counter → MatchEngine） ==================== -->

    <!-- 经风控通过后的内部订单 -->
    <message name="InternalNewOrder" id="201" description="Risk-checked order to matching engine">
        <field name="orderId"        id="1"  type="int64"       description="柜台分配的系统订单ID"/>
        <field name="correlationId"  id="2"  type="int64"       description="原始请求 correlationId"/>
        <field name="accountId"      id="3"  type="int64"       description="账户ID"/>
        <field name="symbolId"       id="4"  type="int32"       description="交易对ID"/>
        <field name="side"           id="5"  type="Side"        description="买卖方向"/>
        <field name="orderType"      id="6"  type="OrderType"   description="订单类型"/>
        <field name="timeInForce"    id="7"  type="TimeInForce" description="有效时间"/>
        <field name="price"          id="8"  type="int64"       description="委托价格"/>
        <field name="quantity"       id="9"  type="int64"       description="委托数量"/>
        <field name="timestamp"      id="10" type="int64"       description="系统接受时间戳（纳秒）"/>
    </message>

    <!-- 内部撤单指令 -->
    <message name="InternalCancelOrder" id="202" description="Cancel order to matching engine">
        <field name="orderId"        id="1"  type="int64"  description="要撤销的订单ID"/>
        <field name="accountId"      id="2"  type="int64"  description="账户ID"/>
        <field name="symbolId"       id="3"  type="int32"  description="交易对ID"/>
        <field name="correlationId"  id="4"  type="int64"  description="原始请求 correlationId"/>
        <field name="timestamp"      id="5"  type="int64"  description="时间戳（纳秒）"/>
    </message>

    <!-- ==================== 撮合回报消息 ==================== -->

    <!-- 撮合成交结果 -->
    <message name="MatchResult" id="301" description="Match execution result from matching engine">
        <field name="sequenceNo"      id="1"  type="int64"  description="撮合全局序列号（单调递增）"/>
        <field name="symbolId"        id="2"  type="int32"  description="交易对ID"/>
        <field name="makerOrderId"    id="3"  type="int64"  description="Maker 订单ID（被动方）"/>
        <field name="takerOrderId"    id="4"  type="int64"  description="Taker 订单ID（主动方）"/>
        <field name="makerAccountId"  id="5"  type="int64"  description="Maker 账户ID"/>
        <field name="takerAccountId"  id="6"  type="int64"  description="Taker 账户ID"/>
        <field name="price"           id="7"  type="int64"  description="成交价格（Maker 价格优先）"/>
        <field name="quantity"        id="8"  type="int64"  description="成交数量"/>
        <field name="makerSide"       id="9"  type="Side"   description="Maker 的买卖方向"/>
        <field name="makerFee"        id="10" type="int64"  description="Maker 手续费（可为负，表示返佣）"/>
        <field name="takerFee"        id="11" type="int64"  description="Taker 手续费"/>
        <field name="timestamp"       id="12" type="int64"  description="成交时间戳（纳秒）"/>
    </message>

    <!-- 订单簿变更事件（用于行情推送） -->
    <message name="OrderBookUpdate" id="302" description="Order book change event for market data">
        <field name="sequenceNo"  id="1"  type="int64"  description="撮合序列号"/>
        <field name="symbolId"    id="2"  type="int32"  description="交易对ID"/>
        <field name="side"        id="3"  type="Side"   description="变化的方向（买盘/卖盘）"/>
        <field name="price"       id="4"  type="int64"  description="变化的价格档位"/>
        <field name="quantity"    id="5"  type="int64"  description="该档位新的总挂单量（0表示该档已清空）"/>
        <field name="timestamp"   id="6"  type="int64"  description="时间戳（纳秒）"/>
    </message>

</messageSchema>
```

### 3.3 生成 SBE 编解码器

```bash
cd trading-platform
mvn generate-sources -pl common/common-sbe -q

# 查看生成的文件
find common/common-sbe/target/generated-sources -name "*.java" | head -20
```

期望输出（类似）：

```
common/common-sbe/target/generated-sources/sbe/com/trading/sbe/MessageHeaderDecoder.java
common/common-sbe/target/generated-sources/sbe/com/trading/sbe/MessageHeaderEncoder.java
common/common-sbe/target/generated-sources/sbe/com/trading/sbe/NewOrderRequestDecoder.java
common/common-sbe/target/generated-sources/sbe/com/trading/sbe/NewOrderRequestEncoder.java
common/common-sbe/target/generated-sources/sbe/com/trading/sbe/ExecutionReportDecoder.java
common/common-sbe/target/generated-sources/sbe/com/trading/sbe/ExecutionReportEncoder.java
...
```

### 3.4 理解 SBE 生成代码（重要）

SBE 编解码器的使用模式与普通 Java 对象不同，**不创建任何新对象**：

```java
// 错误方式（普通 Java 思维，会产生 GC 压力）：
NewOrderRequestEncoder encoder = new NewOrderRequestEncoder(); // 每次 new！

// 正确方式（SBE 方式，预先创建，反复复用）：
// 1. 预分配编码器和 DirectBuffer（只做一次）
UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
NewOrderRequestEncoder orderEncoder = new NewOrderRequestEncoder();

// 2. 编码（零拷贝，直接写入 buffer）
int offset = 0;
headerEncoder.wrap(buffer, offset)
    .blockLength(NewOrderRequestEncoder.BLOCK_LENGTH)
    .templateId(NewOrderRequestEncoder.TEMPLATE_ID)
    .schemaId(NewOrderRequestEncoder.SCHEMA_ID)
    .version(NewOrderRequestEncoder.SCHEMA_VERSION);

orderEncoder.wrap(buffer, offset + MessageHeaderEncoder.ENCODED_LENGTH)
    .correlationId(12345L)
    .accountId(1001L)
    .symbolId(1)
    .side(Side.BUY)
    .orderType(OrderType.LIMIT)
    .timeInForce(TimeInForce.GTC)
    .price(5000000L)     // 50000.00 USDT（精度 0.01）
    .quantity(100000L)   // 1.00000 BTC（精度 0.00001）
    .leverage((short) 1)
    .timestamp(System.nanoTime());

// 3. 解码（同样零拷贝）
MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
NewOrderRequestDecoder orderDecoder = new NewOrderRequestDecoder();

headerDecoder.wrap(buffer, 0);
orderDecoder.wrap(buffer,
    MessageHeaderDecoder.ENCODED_LENGTH,
    headerDecoder.blockLength(),
    headerDecoder.version());

long accountId = orderDecoder.accountId();  // 直接从 buffer 读取，无对象创建
Side side = orderDecoder.side();
```

---

## 4. 实现 common-util 工具库

### 4.1 SnowflakeIdGenerator（订单 ID 生成器）

文件：`common/common-util/src/main/java/com/trading/util/SnowflakeIdGenerator.java`

```java
package com.trading.util;

/**
 * Snowflake 变体订单 ID 生成器。
 *
 * 64 位结构：
 *   [63]       符号位，永远为 0
 *   [62..21]   42 bit 毫秒时间戳（可用约 139 年）
 *   [20..16]   5  bit 节点 ID（0~31，对应 Aeron Cluster 成员 ID）
 *   [15..0]    16 bit 序列号（每毫秒最多 65535 个 ID）
 *
 * 设计要点：
 *   - 单线程使用（撮合引擎/柜台均为单线程调度），无锁
 *   - 生成 ID 单调递增，天然有序，有利于 HashMap/TreeMap 性能
 *   - 节点 ID 区分不同 Cluster 节点，防止多节点冲突
 */
public final class SnowflakeIdGenerator {

    // 各字段位数
    private static final int NODE_ID_BITS    = 5;
    private static final int SEQUENCE_BITS   = 16;

    // 最大值掩码
    private static final long MAX_NODE_ID    = (1L << NODE_ID_BITS) - 1;   // 31
    private static final long MAX_SEQUENCE   = (1L << SEQUENCE_BITS) - 1;  // 65535

    // 左移量
    private static final int  NODE_ID_SHIFT  = SEQUENCE_BITS;               // 16
    private static final int  TIMESTAMP_SHIFT = NODE_ID_BITS + SEQUENCE_BITS; // 21

    // 自定义纪元（2024-01-01 00:00:00 UTC，减小时间戳值）
    private static final long EPOCH_MS = 1704067200000L;

    private final long nodeId;
    private long lastTimestampMs = -1L;
    private long sequence = 0L;

    /**
     * @param nodeId 节点 ID，范围 [0, 31]，对应 Aeron Cluster 成员 ID
     */
    public SnowflakeIdGenerator(final int nodeId) {
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException(
                "nodeId must be in [0, " + MAX_NODE_ID + "], got: " + nodeId);
        }
        this.nodeId = nodeId;
    }

    /**
     * 生成下一个唯一 ID。
     * 注意：此方法非线程安全，只允许单线程调用。
     *
     * @return 64 位唯一单调递增 ID
     */
    public long nextId() {
        long currentMs = System.currentTimeMillis() - EPOCH_MS;

        if (currentMs == lastTimestampMs) {
            // 同一毫秒内，序列号递增
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // 同毫秒序列号溢出，等待下一毫秒
                currentMs = waitNextMillis(lastTimestampMs);
            }
        } else if (currentMs > lastTimestampMs) {
            // 新毫秒，重置序列号
            sequence = 0;
        } else {
            // 时钟回拨（NTP 调整等），抛出异常防止 ID 重复
            throw new IllegalStateException(
                "Clock moved backwards! lastMs=" + lastTimestampMs +
                ", currentMs=" + currentMs);
        }

        lastTimestampMs = currentMs;

        return (currentMs << TIMESTAMP_SHIFT)
             | (nodeId   << NODE_ID_SHIFT)
             | sequence;
    }

    private long waitNextMillis(final long lastMs) {
        long ms;
        do {
            ms = System.currentTimeMillis() - EPOCH_MS;
        } while (ms <= lastMs);
        return ms;
    }
}
```

### 4.2 NanoTimeProvider（纳秒时间提供器）

文件：`common/common-util/src/main/java/com/trading/util/NanoTimeProvider.java`

```java
package com.trading.util;

/**
 * 纳秒级时间提供器。
 *
 * 为什么不直接用 System.nanoTime()？
 *   - 在 Aeron Cluster 中，时间必须来自 Cluster 提供的确定性时钟
 *   - 此接口允许在测试/Cluster 模式下替换时间源
 *   - 避免在撮合引擎中直接依赖 System.nanoTime()，保持确定性
 */
public interface NanoTimeProvider {

    /**
     * 返回当前时间，单位：纳秒（UTC epoch）。
     * 实现必须是单调递增的（不要求绝对精确的 UTC，但必须单调）。
     */
    long nanoTime();

    /**
     * 系统默认实现：使用 System.nanoTime() + 启动时的 epoch 偏移量。
     * 注意：System.nanoTime() 不保证是 UTC epoch，此处做了修正。
     */
    NanoTimeProvider SYSTEM = new NanoTimeProvider() {
        // JVM 启动时记录 epoch 偏移
        private final long epochOffsetNs =
            System.currentTimeMillis() * 1_000_000L - System.nanoTime();

        @Override
        public long nanoTime() {
            return System.nanoTime() + epochOffsetNs;
        }
    };
}
```

### 4.3 PriceUtil（价格精度工具）

文件：`common/common-util/src/main/java/com/trading/util/PriceUtil.java`

```java
package com.trading.util;

/**
 * 价格和数量精度工具类。
 *
 * 系统约定：
 *   - 所有价格和数量在系统内部以 long 存储，代表固定精度整数
 *   - 精度因子（scale）存储在交易对配置（SymbolConfig）中
 *   - 例如：BTC/USDT pricePrecision=2，价格 50000.25 存为 5000025L
 *
 * 为什么用 long 而不是 BigDecimal？
 *   - BigDecimal 每次运算都创建新对象，产生 GC 压力
 *   - long 运算是 CPU 原生指令，比 BigDecimal 快 10~100 倍
 *   - 固定精度能精确表示十进制小数，无浮点误差
 */
public final class PriceUtil {

    // 预计算精度因子表（10^0 ~ 10^18），避免重复计算
    private static final long[] POW10 = new long[19];

    static {
        POW10[0] = 1L;
        for (int i = 1; i < POW10.length; i++) {
            POW10[i] = POW10[i - 1] * 10L;
        }
    }

    private PriceUtil() {}

    /**
     * double → long（用于从外部输入转换，仅在非热路径使用）
     *
     * @param value     double 价格值，如 50000.25
     * @param precision 精度位数，如 2（表示最小单位 0.01）
     * @return long 表示，如 5000025L
     */
    public static long toLong(final double value, final int precision) {
        return Math.round(value * POW10[precision]);
    }

    /**
     * long → double（用于展示/日志，仅在非热路径使用）
     *
     * @param value     long 价格值，如 5000025L
     * @param precision 精度位数，如 2
     * @return double 表示，如 50000.25
     */
    public static double toDouble(final long value, final int precision) {
        return (double) value / POW10[precision];
    }

    /**
     * 计算成交金额（热路径可用）。
     *
     * amount = price × quantity / 10^(pricePrecision + quantityPrecision)
     *
     * @param price              long 价格
     * @param quantity           long 数量
     * @param pricePrecision     价格精度
     * @param quantityPrecision  数量精度
     * @param resultPrecision    期望返回值的精度
     * @return long 成交金额
     */
    public static long calcAmount(final long price,
                                  final long quantity,
                                  final int  pricePrecision,
                                  final int  quantityPrecision,
                                  final int  resultPrecision) {
        // 使用 128 位中间值防止溢出（Java 没有 uint128，用两步除法）
        // price * quantity 可能超过 long，需小心
        // 简化版本（适用于 price/quantity 不超过 10^12 的场景）：
        return Math.multiplyHigh(price, quantity) == 0
            ? (price * quantity * POW10[resultPrecision]) / POW10[pricePrecision + quantityPrecision]
            : (long) ((double) price * quantity / POW10[pricePrecision + quantityPrecision - resultPrecision]);
    }

    /**
     * 计算手续费（热路径可用）。
     *
     * fee = amount × feeRate / 10^feeRatePrecision
     *
     * feeRate 约定：以 10^-6 为单位（百万分之一）
     *   例如：0.1% = 1000（即 1000 / 1_000_000 = 0.001）
     *         0.01% = 100
     *
     * @param amount           成交金额（long）
     * @param feeRateMicros    手续费率（单位 1/1_000_000）
     * @param amountPrecision  成交金额精度
     * @return long 手续费（与 amount 同精度）
     */
    public static long calcFee(final long amount,
                               final int  feeRateMicros,
                               final int  amountPrecision) {
        // fee = amount * feeRateMicros / 1_000_000
        return (amount * feeRateMicros) / 1_000_000L;
    }

    /**
     * 对价格进行 tick 对齐（价格必须是 priceTick 的整数倍）。
     *
     * @param price     原始价格
     * @param priceTick 最小价格变动单位
     * @return 对齐后的价格（向下取整）
     */
    public static long alignToTick(final long price, final long priceTick) {
        return (price / priceTick) * priceTick;
    }

    /**
     * 检查价格是否是 tick 的整数倍。
     */
    public static boolean isValidTick(final long price, final long priceTick) {
        return price % priceTick == 0;
    }
}
```

### 4.4 ObjectPool（通用对象池）

文件：`common/common-util/src/main/java/com/trading/util/ObjectPool.java`

```java
package com.trading.util;

import java.util.function.Supplier;

/**
 * 轻量级单线程对象池。
 *
 * 设计目标：
 *   - 消除热路径对象分配，实现零 GC
 *   - 非线程安全，只允许单线程使用（撮合引擎是单线程的）
 *   - 底层使用 Object[] 栈，避免泛型装箱
 *
 * 用法示例：
 *   // 初始化（系统启动时，预分配 1024 个 OrderNode）
 *   ObjectPool<OrderNode> pool = new ObjectPool<>(OrderNode::new, 1024);
 *
 *   // 取出
 *   OrderNode node = pool.borrow();
 *   node.reset(orderId, price, quantity);
 *
 *   // 归还（成交/撤销后）
 *   pool.release(node);
 */
public final class ObjectPool<T> {

    private final Object[] pool;
    private int top;  // 栈顶指针

    /**
     * @param factory  对象工厂（只在初始化时调用）
     * @param capacity 预分配容量
     */
    @SuppressWarnings("unchecked")
    public ObjectPool(final Supplier<T> factory, final int capacity) {
        this.pool = new Object[capacity];
        this.top = capacity;  // 满池
        for (int i = 0; i < capacity; i++) {
            pool[i] = factory.get();
        }
    }

    /**
     * 从池中借出一个对象。
     * 如果池已空，返回 null（调用方需处理）。
     *
     * @return 对象实例，或 null（池空时）
     */
    @SuppressWarnings("unchecked")
    public T borrow() {
        if (top == 0) {
            return null;  // 池已空，需扩容或报警
        }
        return (T) pool[--top];
    }

    /**
     * 将对象归还到池中。
     * 调用方需在归还前重置对象状态，防止脏数据。
     *
     * @param obj 要归还的对象
     */
    public void release(final T obj) {
        if (top < pool.length) {
            pool[top++] = obj;
        }
        // 超出容量则丢弃（不应发生，可加监控）
    }

    /** 当前可用对象数量 */
    public int available() {
        return top;
    }

    /** 池总容量 */
    public int capacity() {
        return pool.length;
    }

    /** 是否为空 */
    public boolean isEmpty() {
        return top == 0;
    }
}
```

### 4.5 工具类单元测试

文件：`common/common-util/src/test/java/com/trading/util/SnowflakeIdGeneratorTest.java`

```java
package com.trading.util;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SnowflakeIdGeneratorTest {

    @Test
    void shouldGenerateUniqueIds() {
        final SnowflakeIdGenerator gen = new SnowflakeIdGenerator(0);
        final int count = 100_000;
        final Set<Long> ids = new HashSet<>(count);

        for (int i = 0; i < count; i++) {
            assertTrue(ids.add(gen.nextId()), "Duplicate ID detected at iteration " + i);
        }
    }

    @Test
    void shouldGenerateMonotonicallyIncreasingIds() {
        final SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1);
        long prev = gen.nextId();
        for (int i = 0; i < 10_000; i++) {
            long curr = gen.nextId();
            assertTrue(curr > prev, "ID not monotonically increasing: prev=" + prev + ", curr=" + curr);
            prev = curr;
        }
    }

    @Test
    void shouldGenerateUniqueIdsAcrossNodes() {
        final SnowflakeIdGenerator gen0 = new SnowflakeIdGenerator(0);
        final SnowflakeIdGenerator gen1 = new SnowflakeIdGenerator(1);
        // 同一时刻不同节点生成的 ID 不应冲突
        assertNotEquals(gen0.nextId(), gen1.nextId());
    }
}
```

文件：`common/common-util/src/test/java/com/trading/util/PriceUtilTest.java`

```java
package com.trading.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PriceUtilTest {

    @Test
    void shouldConvertDoubleToLong() {
        // 50000.25 USDT，精度 2 → 5000025L
        assertEquals(5000025L, PriceUtil.toLong(50000.25, 2));
        assertEquals(100000L,  PriceUtil.toLong(1.00000, 5));  // 1 BTC，精度 5
    }

    @Test
    void shouldConvertLongToDouble() {
        assertEquals(50000.25, PriceUtil.toDouble(5000025L, 2), 1e-10);
        assertEquals(1.0,      PriceUtil.toDouble(100000L, 5),  1e-10);
    }

    @Test
    void shouldAlignToTick() {
        // tick=100 (0.01 精度下的最小单位 1)，对齐
        assertEquals(5000000L, PriceUtil.alignToTick(5000025L, 100L));
        assertEquals(5000100L, PriceUtil.alignToTick(5000100L, 100L));
    }

    @Test
    void shouldValidateTick() {
        assertTrue(PriceUtil.isValidTick(5000000L, 100L));
        assertFalse(PriceUtil.isValidTick(5000025L, 100L));
    }
}
```

### 4.6 运行单元测试

```bash
cd trading-platform
mvn test -pl common/common-util
# 期望：Tests run: 5, Failures: 0, Errors: 0
```

---

## 5. 实现 Aeron IPC 基础链路 Demo

### 5.1 Aeron 核心概念

```
Media Driver：独立的 I/O 进程（或内嵌线程），管理实际的内存映射文件和网络套接字。
              所有发布/订阅都通过 Media Driver 中转。

Publication：消息发布端，写入消息到 Log Buffer（映射文件）。

Subscription：消息订阅端，读取 Log Buffer，调用 FragmentHandler 处理消息。

Fragment：Aeron 中每次读取的最小数据单元，对应一次 offer 写入的数据。

Channel：通信频道描述符，IPC 频道为 "aeron:ipc"，UDP 频道为 "aeron:udp?endpoint=..."。

Stream：同一 Channel 下的逻辑流，用 streamId（int）区分，类似 Kafka 的 Topic。
```

### 5.2 Aeron IPC 基础 Demo

文件：`matching-engine/src/main/java/com/trading/matching/demo/AeronIpcDemo.java`

```java
package com.trading.matching.demo;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aeron IPC 基础链路 Demo。
 *
 * 演示：
 *   1. 在同一进程内启动 Embedded Media Driver
 *   2. 创建 Publication 和 Subscription（IPC 通道）
 *   3. Publisher 线程持续发送消息
 *   4. Subscriber 线程持续接收消息
 *   5. 统计吞吐量和确认消息完整性
 *
 * 运行方式：
 *   mvn exec:java -pl matching-engine \
 *     -Dexec.mainClass="com.trading.matching.demo.AeronIpcDemo" \
 *     -Daeron.dir=/tmp/aeron
 */
public class AeronIpcDemo {

    private static final Logger log = LoggerFactory.getLogger(AeronIpcDemo.class);

    // IPC 通道（同进程内，零拷贝共享内存）
    private static final String CHANNEL   = "aeron:ipc";
    private static final int    STREAM_ID = 1;

    // Demo 参数
    private static final int  MESSAGE_COUNT    = 1_000_000;  // 发送 100 万条消息
    private static final int  MESSAGE_LENGTH   = 64;          // 每条消息 64 字节
    private static final long WARMUP_COUNT     = 10_000;      // 预热 1 万条

    public static void main(final String[] args) throws Exception {

        // 1. 配置并启动 Embedded Media Driver
        //    DEDICATED 模式：Conductor/Sender/Receiver 各自独立线程
        //    SHARED 模式：所有角色共用一个线程（低延迟场景不推荐）
        final MediaDriver.Context driverCtx = new MediaDriver.Context()
            .dirDeleteOnStart(true)           // 启动时清理旧的 aeron 目录
            .dirDeleteOnShutdown(true)        // 关闭时清理
            .threadingMode(ThreadingMode.DEDICATED)  // 专用线程模式
            .conductorIdleStrategy(new BusySpinIdleStrategy())   // Conductor 忙轮询
            .senderIdleStrategy(new BusySpinIdleStrategy())      // Sender 忙轮询
            .receiverIdleStrategy(new BusySpinIdleStrategy())    // Receiver 忙轮询
            .aeronDirectoryName(System.getProperty("aeron.dir", "/tmp/aeron-demo"));

        log.info("Starting Aeron Media Driver at: {}", driverCtx.aeronDirectoryName());

        try (MediaDriver driver = MediaDriver.launch(driverCtx)) {

            // 2. 创建 Aeron 客户端
            final Aeron.Context aeronCtx = new Aeron.Context()
                .aeronDirectoryName(driverCtx.aeronDirectoryName());

            try (Aeron aeron = Aeron.connect(aeronCtx);
                 Publication publication = aeron.addPublication(CHANNEL, STREAM_ID);
                 Subscription subscription = aeron.addSubscription(CHANNEL, STREAM_ID)) {

                // 等待 Publication 和 Subscription 连接
                waitForConnection(publication, subscription);
                log.info("Publication and Subscription connected. Starting demo...");

                // 3. 启动 Subscriber 线程
                final AtomicLong receivedCount = new AtomicLong(0);
                final CountDownLatch doneLatch = new CountDownLatch(1);

                final Thread subscriberThread = new Thread(() ->
                    runSubscriber(subscription, receivedCount, MESSAGE_COUNT, doneLatch),
                    "subscriber-thread"
                );
                subscriberThread.setDaemon(true);
                subscriberThread.start();

                // 4. 预热（避免 JIT 编译影响测量结果）
                final UnsafeBuffer sendBuffer = new UnsafeBuffer(new byte[MESSAGE_LENGTH]);
                log.info("Warming up with {} messages...", WARMUP_COUNT);
                for (int i = 0; i < WARMUP_COUNT; i++) {
                    sendBuffer.putLong(0, i);
                    sendMessage(publication, sendBuffer, MESSAGE_LENGTH);
                }

                // 等待预热消息消费完
                Thread.sleep(500);
                receivedCount.set(0);
                log.info("Warmup complete. Starting measurement...");

                // 5. 正式发送测量消息
                final long startNs = System.nanoTime();
                for (int i = 0; i < MESSAGE_COUNT; i++) {
                    sendBuffer.putLong(0, i);  // 消息内容：序列号
                    sendBuffer.putLong(8, System.nanoTime());  // 发送时间戳
                    sendMessage(publication, sendBuffer, MESSAGE_LENGTH);
                }
                final long sendEndNs = System.nanoTime();

                // 6. 等待所有消息被消费
                doneLatch.await();
                final long totalNs = System.nanoTime() - startNs;

                // 7. 打印统计结果
                printStats(MESSAGE_COUNT, totalNs, sendEndNs - startNs);
            }
        }
    }

    /**
     * 发送单条消息，处理背压（BACK_PRESSURED）和管理员动作（ADMIN_ACTION）。
     */
    private static void sendMessage(final Publication pub,
                                    final DirectBuffer buf,
                                    final int length) {
        long result;
        // 忙等待直到发送成功（热路径，使用忙轮询）
        while ((result = pub.offer(buf, 0, length)) < 0) {
            if (result == Publication.CLOSED || result == Publication.NOT_CONNECTED) {
                throw new RuntimeException("Publication not available: " + result);
            }
            // BACK_PRESSURED 或 ADMIN_ACTION：继续重试
            Thread.onSpinWait();  // Java 9+ CPU hint，提示处理器当前在自旋等待
        }
    }

    /**
     * Subscriber 持续轮询消息直到收到指定数量。
     */
    private static void runSubscriber(final Subscription subscription,
                                      final AtomicLong receivedCount,
                                      final long targetCount,
                                      final CountDownLatch doneLatch) {
        final IdleStrategy idleStrategy = new BusySpinIdleStrategy();

        // FragmentHandler：每次收到一个 Fragment 时调用
        final FragmentHandler handler = (buffer, offset, length, header) -> {
            // 这里是消息处理热路径
            // buffer：包含消息数据的 DirectBuffer（Aeron 管理，不要持有引用）
            // offset：消息在 buffer 中的起始位置
            // length：消息长度
            final long seqNo = buffer.getLong(offset);      // 读取序列号（零拷贝）
            final long sendTs = buffer.getLong(offset + 8); // 读取发送时间戳

            final long count = receivedCount.incrementAndGet();
            if (count == targetCount) {
                doneLatch.countDown();
            }
        };

        long count = receivedCount.get();
        while (count < targetCount) {
            // poll：尝试读取最多 10 个 Fragment，返回实际读取数量
            final int fragmentsRead = subscription.poll(handler, 10);
            idleStrategy.idle(fragmentsRead);
            count = receivedCount.get();
        }
    }

    /**
     * 等待 Publication 和 Subscription 建立连接。
     */
    private static void waitForConnection(final Publication pub,
                                          final Subscription sub) throws InterruptedException {
        final long timeoutMs = 5000;
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (!pub.isConnected() || !sub.isConnected()) {
            if (System.currentTimeMillis() > deadline) {
                throw new RuntimeException("Connection timeout after " + timeoutMs + "ms");
            }
            Thread.sleep(10);
        }
    }

    private static void printStats(final long msgCount,
                                   final long totalNs,
                                   final long sendOnlyNs) {
        final double totalMs = totalNs / 1e6;
        final double sendMs  = sendOnlyNs / 1e6;
        final double tps     = msgCount / (totalNs / 1e9);
        final double avgNs   = (double) totalNs / msgCount;

        log.info("=== Aeron IPC Demo Results ===");
        log.info("Messages       : {}", msgCount);
        log.info("Total time     : {:.2f} ms", totalMs);
        log.info("Send time      : {:.2f} ms", sendMs);
        log.info("Throughput     : {:.0f} msg/sec", tps);
        log.info("Avg latency    : {:.2f} ns/msg", avgNs);
    }
}
```

### 5.3 运行 Aeron IPC Demo

```bash
# 确保 Aeron 临时目录存在
mkdir -p /tmp/aeron-demo

cd trading-platform

# 编译
mvn compile -pl matching-engine -am -q

# 运行 Demo
mvn exec:java -pl matching-engine \
  -Dexec.mainClass="com.trading.matching.demo.AeronIpcDemo" \
  -Dexec.args="" \
  "-Daeron.dir=/tmp/aeron-demo" \
  -Dexec.jvmArgs="--add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED -Xms512m -Xmx512m"
```

**期望输出（macOS ARM，参考值）：**

```
[main] INFO  Starting Aeron Media Driver at: /tmp/aeron-demo
[main] INFO  Publication and Subscription connected. Starting demo...
[main] INFO  Warming up with 10000 messages...
[main] INFO  Warmup complete. Starting measurement...
[main] INFO  === Aeron IPC Demo Results ===
[main] INFO  Messages       : 1000000
[main] INFO  Total time     : 312.45 ms
[main] INFO  Send time      : 298.12 ms
[main] INFO  Throughput     : 3,201,845 msg/sec
[main] INFO  Avg latency    : 312.45 ns/msg
```

> **注意：** macOS 上 Aeron IPC 不使用真正的共享内存（无 `/dev/shm`），延迟比 Linux 稍高。Linux 服务器上可达 100~200ns 级别。

---

## 6. 实现 Disruptor Pipeline Demo

### 6.1 Disruptor 核心概念

```
RingBuffer：预分配的环形数组，所有事件对象预先创建并复用，零 GC。

Event：放入 RingBuffer 的数据载体，必须预先创建（EventFactory）。

EventHandler：消费者，处理 RingBuffer 中的事件。

SequenceBarrier：消费者等待生产者（或上游消费者）的同步屏障。

WaitStrategy：消费者等待新事件时的策略（BusySpin/Yielding/Sleeping/Blocking）。

Disruptor 依赖图（Handler 之间的依赖关系）：
  A → B    B 在 A 之后处理
  A,B → C  C 在 A 和 B 都完成后处理（菱形依赖）
  A → B,C  B 和 C 并行处理（fanout）
```

### 6.2 撮合引擎 Disruptor Pipeline Demo

文件：`matching-engine/src/main/java/com/trading/matching/demo/DisruptorPipelineDemo.java`

```java
package com.trading.matching.demo;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.hdrhistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;

/**
 * Disruptor Pipeline Demo。
 *
 * 模拟撮合引擎的 3 段 Pipeline：
 *
 *   [Producer]
 *       ↓
 *   Stage 1: SequenceAssignHandler  （分配序列号）
 *       ↓
 *   Stage 2: MatchingHandler        （模拟撮合）
 *       ↓
 *   Stage 3a: JournalHandler        （模拟持久化，并行）
 *   Stage 3b: ExecReportHandler     （模拟回报，并行）
 *
 * 架构说明：
 *   - Stage 1 和 Stage 2 串行（保证撮合有序）
 *   - Stage 3a 和 Stage 3b 并行（日志和回报可同时进行）
 *   - 整体使用 BusySpin，追求最低延迟
 */
public class DisruptorPipelineDemo {

    private static final Logger log = LoggerFactory.getLogger(DisruptorPipelineDemo.class);

    private static final int  RING_BUFFER_SIZE = 1 << 20;    // 2^20 = 1,048,576
    private static final int  MESSAGE_COUNT    = 1_000_000;
    private static final long WARMUP_COUNT     = 50_000;

    // ========================= 事件定义 =========================

    /**
     * RingBuffer 中的事件对象（预分配，反复复用）。
     *
     * 包含了整个 Pipeline 各阶段需要的所有字段。
     * 每个阶段只写自己负责的字段，读取上游阶段写入的字段。
     */
    static final class OrderEvent {
        // 生产者填充
        long orderId;
        long accountId;
        int  symbolId;
        byte side;          // 1=Buy, 2=Sell
        long price;
        long quantity;
        long sendTimestampNs;  // 发送时间戳（用于测量延迟）

        // Stage 1: SequenceAssignHandler 填充
        long matchSequenceNo;

        // Stage 2: MatchingHandler 填充
        boolean matched;
        long matchPrice;
        long matchQuantity;
        long matchTimestampNs;

        // Stage 3: Handlers 消费，不写入
    }

    /** EventFactory：Disruptor 启动时调用，预分配所有事件对象 */
    static final class OrderEventFactory implements EventFactory<OrderEvent> {
        @Override
        public OrderEvent newInstance() {
            return new OrderEvent();
        }
    }

    // ========================= Stage 1: 序列号分配 =========================

    static final class SequenceAssignHandler implements EventHandler<OrderEvent> {
        private long sequenceCounter = 0L;

        @Override
        public void onEvent(final OrderEvent event,
                            final long sequence,
                            final boolean endOfBatch) {
            // 分配全局单调递增撮合序列号
            event.matchSequenceNo = ++sequenceCounter;
        }
    }

    // ========================= Stage 2: 模拟撮合 =========================

    static final class MatchingHandler implements EventHandler<OrderEvent> {
        // 模拟：买单价格 >= 卖盘最优价时成交
        private long bestAskPrice = 50000_00L;  // 50000.00（精度 0.01，用 long 存储）

        @Override
        public void onEvent(final OrderEvent event,
                            final long sequence,
                            final boolean endOfBatch) {
            // 模拟撮合逻辑（简化）
            if (event.side == 1 && event.price >= bestAskPrice) {
                event.matched = true;
                event.matchPrice = bestAskPrice;
                event.matchQuantity = event.quantity;
                event.matchTimestampNs = System.nanoTime();
                // 模拟 ask 价格变动
                bestAskPrice += 1;
            } else {
                event.matched = false;
            }
        }
    }

    // ========================= Stage 3a: 模拟 Journal =========================

    static final class JournalHandler implements EventHandler<OrderEvent> {
        private long journalCount = 0L;

        @Override
        public void onEvent(final OrderEvent event,
                            final long sequence,
                            final boolean endOfBatch) {
            // 模拟写日志：只记录有成交的事件
            if (event.matched) {
                journalCount++;
                // 实际实现中，这里会调用 Aeron Publication 将事件写入 Journal Service
            }
        }

        long getJournalCount() { return journalCount; }
    }

    // ========================= Stage 3b: 模拟回报 =========================

    static final class ExecReportHandler implements EventHandler<OrderEvent> {
        private final Histogram latencyHistogram;
        private final CountDownLatch doneLatch;
        private final long targetCount;
        private long processedCount = 0L;

        ExecReportHandler(final Histogram histogram,
                          final CountDownLatch latch,
                          final long target) {
            this.latencyHistogram = histogram;
            this.doneLatch = latch;
            this.targetCount = target;
        }

        @Override
        public void onEvent(final OrderEvent event,
                            final long sequence,
                            final boolean endOfBatch) {
            // 测量端到端延迟（从生产者 offer 到此 Handler 处理）
            final long latencyNs = System.nanoTime() - event.sendTimestampNs;
            latencyHistogram.recordValue(Math.min(latencyNs, latencyHistogram.getHighestTrackableValue()));

            processedCount++;
            if (processedCount >= targetCount) {
                doneLatch.countDown();
            }
        }
    }

    // ========================= Main =========================

    public static void main(final String[] args) throws Exception {

        final Histogram latencyHistogram = new Histogram(1, 10_000_000L, 3); // 1ns ~ 10ms
        final CountDownLatch doneLatch = new CountDownLatch(1);

        // 1. 创建 Disruptor
        //    ProducerType.SINGLE：只有一个生产者线程（撮合引擎场景）
        //    BusySpinWaitStrategy：消费者忙轮询，最低延迟
        final ThreadFactory threadFactory = r -> {
            final Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        };

        final Disruptor<OrderEvent> disruptor = new Disruptor<>(
            new OrderEventFactory(),
            RING_BUFFER_SIZE,
            threadFactory,
            ProducerType.SINGLE,    // 单生产者（更快，无需 CAS）
            new BusySpinWaitStrategy()
        );

        // 2. 配置 Pipeline 依赖关系
        final SequenceAssignHandler stage1 = new SequenceAssignHandler();
        final MatchingHandler       stage2 = new MatchingHandler();
        final JournalHandler        stage3a = new JournalHandler();
        final ExecReportHandler     stage3b = new ExecReportHandler(
            latencyHistogram, doneLatch, MESSAGE_COUNT);

        disruptor
            .handleEventsWith(stage1)       // Stage 1 先执行
            .then(stage2)                   // Stage 2 在 Stage 1 之后
            .then(stage3a, stage3b);        // Stage 3a 和 3b 并行（等 Stage 2 完成后）

        // 3. 启动 Disruptor（启动所有消费者线程）
        final RingBuffer<OrderEvent> ringBuffer = disruptor.start();
        log.info("Disruptor started. RingBuffer size: {}", RING_BUFFER_SIZE);

        // 4. 预热
        log.info("Warming up with {} events...", WARMUP_COUNT);
        publishEvents(ringBuffer, (int) WARMUP_COUNT);
        Thread.sleep(200);
        latencyHistogram.reset();
        log.info("Warmup complete.");

        // 5. 正式测量
        log.info("Starting measurement with {} events...", MESSAGE_COUNT);
        final long startNs = System.nanoTime();
        publishEvents(ringBuffer, MESSAGE_COUNT);
        doneLatch.await();
        final long totalNs = System.nanoTime() - startNs;

        // 6. 打印结果
        printResults(MESSAGE_COUNT, totalNs, latencyHistogram, stage3a);

        disruptor.shutdown();
    }

    /**
     * 生产者：批量发布事件到 RingBuffer。
     *
     * 使用 publishEvent + lambda 方式（推荐，避免对象创建）。
     * Lambda 中的 event 是预分配的 OrderEvent 对象，直接填充字段即可。
     */
    private static void publishEvents(final RingBuffer<OrderEvent> ringBuffer,
                                      final int count) {
        for (int i = 0; i < count; i++) {
            // tryPublishEvent：非阻塞，RingBuffer 满时返回 false
            // publishEvent：阻塞等待（适合压测场景）
            ringBuffer.publishEvent((event, sequence) -> {
                event.orderId         = sequence;
                event.accountId       = 1001L;
                event.symbolId        = 1;
                event.side            = (byte) (sequence % 2 == 0 ? 1 : 2);  // 交替买卖
                event.price           = 5000000L + (sequence % 100) * 100;   // 模拟不同价格
                event.quantity        = 100000L;
                event.sendTimestampNs = System.nanoTime();
            });
        }
    }

    private static void printResults(final long msgCount,
                                     final long totalNs,
                                     final Histogram histogram,
                                     final JournalHandler journalHandler) {
        final double totalMs = totalNs / 1e6;
        final double tps     = msgCount / (totalNs / 1e9);

        log.info("=== Disruptor Pipeline Demo Results ===");
        log.info("Messages processed  : {}", msgCount);
        log.info("Journal events      : {}", journalHandler.getJournalCount());
        log.info("Total time          : {:.2f} ms", totalMs);
        log.info("Throughput          : {:.0f} events/sec", tps);
        log.info("--- End-to-End Latency Distribution ---");
        log.info("Min                 : {} ns", histogram.getMinValue());
        log.info("P50 (median)        : {} ns", histogram.getValueAtPercentile(50));
        log.info("P95                 : {} ns", histogram.getValueAtPercentile(95));
        log.info("P99                 : {} ns", histogram.getValueAtPercentile(99));
        log.info("P99.9               : {} ns", histogram.getValueAtPercentile(99.9));
        log.info("P99.99              : {} ns", histogram.getValueAtPercentile(99.99));
        log.info("Max                 : {} ns", histogram.getMaxValue());
    }
}
```

### 6.3 运行 Disruptor Pipeline Demo

```bash
cd trading-platform
mvn compile -pl matching-engine -am -q

mvn exec:java -pl matching-engine \
  -Dexec.mainClass="com.trading.matching.demo.DisruptorPipelineDemo" \
  -Dexec.jvmArgs="-Xms256m -Xmx256m --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
```

**期望输出（参考值）：**

```
[main] INFO  Disruptor started. RingBuffer size: 1048576
[main] INFO  Warming up with 50000 events...
[main] INFO  Warmup complete.
[main] INFO  Starting measurement with 1000000 events...
[main] INFO  === Disruptor Pipeline Demo Results ===
[main] INFO  Messages processed  : 1000000
[main] INFO  Journal events      : 500000
[main] INFO  Total time          : 187.34 ms
[main] INFO  Throughput          : 5,337,248 events/sec
[main] INFO  --- End-to-End Latency Distribution ---
[main] INFO  Min                 : 89 ns
[main] INFO  P50 (median)        : 134 ns
[main] INFO  P95                 : 278 ns
[main] INFO  P99                 : 512 ns
[main] INFO  P99.9               : 1,024 ns
[main] INFO  P99.99              : 4,096 ns
[main] INFO  Max                 : 32,768 ns
```

---

## 7. 完整链路验证：Aeron IPC + Disruptor

### 7.1 完整链路架构

这一步将 Aeron IPC 和 Disruptor 串联，模拟真实的消息从"外部进入"→"通过 Disruptor 处理"→"通过 Aeron 输出"完整路径：

```
[Producer Thread]
    │ Aeron IPC Publication (stream=1)
    │
[Inbound Subscriber Thread]  ← 读取 Aeron IPC 消息
    │ RingBuffer.publish()
    │
[Disruptor Stage 1: SequenceAssign]
    │
[Disruptor Stage 2: Processing]
    │
[Disruptor Stage 3: Aeron IPC Publication (stream=2)]
    │
[Outbound Subscriber Thread]  ← 读取处理结果
    │
[Result Validator]
```

文件：`matching-engine/src/main/java/com/trading/matching/demo/FullPipelineDemo.java`

```java
package com.trading.matching.demo;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.*;
import org.hdrhistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 完整链路 Demo：Aeron IPC → Disruptor Pipeline → Aeron IPC
 *
 * 此 Demo 验证：
 *   1. 外部消息通过 Aeron IPC 进入系统
 *   2. Disruptor Pipeline 处理消息（3段）
 *   3. 处理结果通过 Aeron IPC 输出
 *   4. 端到端延迟测量
 *
 * 这是 Phase 1 的最终验证目标：
 *   单链路端到端延迟 P99 < 1μs（Linux 物理机）
 */
public class FullPipelineDemo {

    private static final Logger log = LoggerFactory.getLogger(FullPipelineDemo.class);

    private static final String INBOUND_CHANNEL  = "aeron:ipc";
    private static final int    INBOUND_STREAM   = 10;
    private static final String OUTBOUND_CHANNEL = "aeron:ipc";
    private static final int    OUTBOUND_STREAM  = 11;

    private static final int  RING_BUFFER_SIZE = 1 << 20;
    private static final int  MESSAGE_COUNT    = 500_000;

    // ---- 消息格式（直接在 DirectBuffer 上操作）----
    // Offset 0  : int64 sequenceNo（生产者填写）
    // Offset 8  : int64 sendTimestampNs（生产者填写）
    // Offset 16 : int64 processedSequenceNo（Disruptor Stage 1 填写）
    // Offset 24 : int64 processedTimestampNs（Disruptor Stage 2 填写）
    // Total: 32 bytes

    static final int FIELD_SEQ_NO        = 0;
    static final int FIELD_SEND_TS       = 8;
    static final int FIELD_PROC_SEQ_NO   = 16;
    static final int FIELD_PROC_TS       = 24;
    static final int MSG_LENGTH          = 32;

    // ---- 事件定义 ----
    static final class PipelineEvent {
        // 从 Aeron 读入的原始数据（复制到 DirectBuffer）
        final MutableDirectBuffer data = new UnsafeBuffer(new byte[MSG_LENGTH]);
    }

    static final class PipelineEventFactory implements EventFactory<PipelineEvent> {
        @Override
        public PipelineEvent newInstance() { return new PipelineEvent(); }
    }

    public static void main(final String[] args) throws Exception {

        final String aeronDir = System.getProperty("aeron.dir", "/tmp/aeron-full-demo");
        final Histogram latencyHistogram = new Histogram(1, 100_000_000L, 3);
        final CountDownLatch doneLatch   = new CountDownLatch(1);
        final AtomicLong receivedCount   = new AtomicLong(0);

        // 1. 启动 Media Driver
        final MediaDriver.Context driverCtx = new MediaDriver.Context()
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .threadingMode(ThreadingMode.DEDICATED)
            .conductorIdleStrategy(new BusySpinIdleStrategy())
            .senderIdleStrategy(new BusySpinIdleStrategy())
            .receiverIdleStrategy(new BusySpinIdleStrategy())
            .aeronDirectoryName(aeronDir);

        try (MediaDriver driver = MediaDriver.launch(driverCtx);
             Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir))) {

            // 2. 创建 Aeron 通道
            try (Publication  inboundPub  = aeron.addPublication (INBOUND_CHANNEL,  INBOUND_STREAM);
                 Subscription inboundSub  = aeron.addSubscription (INBOUND_CHANNEL,  INBOUND_STREAM);
                 Publication  outboundPub = aeron.addPublication (OUTBOUND_CHANNEL, OUTBOUND_STREAM);
                 Subscription outboundSub = aeron.addSubscription(OUTBOUND_CHANNEL, OUTBOUND_STREAM)) {

                waitForConnection(inboundPub, inboundSub);
                waitForConnection(outboundPub, outboundSub);

                // 3. 创建 Disruptor
                final Disruptor<PipelineEvent> disruptor = new Disruptor<>(
                    new PipelineEventFactory(),
                    RING_BUFFER_SIZE,
                    (Runnable r) -> new Thread(r, "disruptor-worker"),
                    ProducerType.SINGLE,
                    new BusySpinWaitStrategy()
                );

                // Stage 1：读取 Aeron 消息并填充 RingBuffer（由 AeronInboundSubscriber 直接 publish）
                // Stage 2：处理逻辑（分配序列号）
                // Stage 3：写出到 Aeron IPC outbound

                final RingBuffer<PipelineEvent> ringBuffer = disruptor
                    .handleEventsWith(
                        // Stage 1：标记序列号
                        (event, seq, eob) -> event.data.putLong(FIELD_PROC_SEQ_NO, seq)
                    )
                    .then(
                        // Stage 2：模拟处理（记录处理时间戳）
                        (event, seq, eob) -> event.data.putLong(FIELD_PROC_TS, System.nanoTime())
                    )
                    .then(
                        // Stage 3：写出到 Aeron outbound
                        (event, seq, eob) -> {
                            long result;
                            while ((result = outboundPub.offer(event.data, 0, MSG_LENGTH)) < 0) {
                                if (result == Publication.CLOSED) break;
                                Thread.onSpinWait();
                            }
                        }
                    )
                    .asRingBuffer();

                disruptor.start();

                // 4. 启动 Outbound Subscriber（接收处理结果并测量延迟）
                final Thread outboundThread = new Thread(() -> {
                    final FragmentHandler outboundHandler = (buffer, offset, length, header) -> {
                        final long sendTs  = buffer.getLong(offset + FIELD_SEND_TS);
                        final long procTs  = buffer.getLong(offset + FIELD_PROC_TS);
                        final long latency = procTs - sendTs;
                        latencyHistogram.recordValue(Math.min(latency, 100_000_000L));
                        if (receivedCount.incrementAndGet() >= MESSAGE_COUNT) {
                            doneLatch.countDown();
                        }
                    };
                    final IdleStrategy idle = new BusySpinIdleStrategy();
                    while (receivedCount.get() < MESSAGE_COUNT) {
                        idle.idle(outboundSub.poll(outboundHandler, 10));
                    }
                }, "outbound-subscriber");
                outboundThread.setDaemon(true);
                outboundThread.start();

                // 5. 启动 Inbound Subscriber（读取 Aeron 消息后 publish 到 Disruptor）
                final Thread inboundThread = new Thread(() -> {
                    final FragmentHandler inboundHandler = (buffer, offset, length, header) -> {
                        // 将 Aeron 消息复制到 RingBuffer 槽位
                        ringBuffer.publishEvent((event, seq) -> {
                            event.data.putBytes(0, buffer, offset, length);
                        });
                    };
                    final IdleStrategy idle = new BusySpinIdleStrategy();
                    // 只需处理 MESSAGE_COUNT 条消息
                    final AtomicLong inCount = new AtomicLong(0);
                    while (inCount.get() < MESSAGE_COUNT) {
                        idle.idle(inboundSub.poll(inboundHandler, 10));
                    }
                }, "inbound-subscriber");
                inboundThread.setDaemon(true);
                inboundThread.start();

                // 6. 主线程发送消息
                Thread.sleep(100); // 等各线程就绪
                log.info("Starting full pipeline test, {} messages...", MESSAGE_COUNT);

                final UnsafeBuffer sendBuffer = new UnsafeBuffer(new byte[MSG_LENGTH]);
                final long startNs = System.nanoTime();

                for (int i = 0; i < MESSAGE_COUNT; i++) {
                    sendBuffer.putLong(FIELD_SEQ_NO,  i);
                    sendBuffer.putLong(FIELD_SEND_TS, System.nanoTime());
                    long result;
                    while ((result = inboundPub.offer(sendBuffer, 0, MSG_LENGTH)) < 0) {
                        if (result == Publication.CLOSED) break;
                        Thread.onSpinWait();
                    }
                }

                doneLatch.await();
                final long totalNs = System.nanoTime() - startNs;

                // 7. 打印结果
                printResults(MESSAGE_COUNT, totalNs, latencyHistogram);

                disruptor.shutdown();
            }
        }
    }

    private static void waitForConnection(final Publication pub,
                                          final Subscription sub) throws InterruptedException {
        while (!pub.isConnected() || !sub.isConnected()) {
            Thread.sleep(1);
        }
    }

    private static void printResults(final long count,
                                     final long totalNs,
                                     final Histogram h) {
        log.info("=== Full Pipeline (Aeron IPC + Disruptor) Results ===");
        log.info("Messages       : {}", count);
        log.info("Total time     : {:.2f} ms", totalNs / 1e6);
        log.info("Throughput     : {:.0f} msg/sec", count / (totalNs / 1e9));
        log.info("--- End-to-End Latency (send → Stage2 processing) ---");
        log.info("P50            : {} ns",  h.getValueAtPercentile(50));
        log.info("P95            : {} ns",  h.getValueAtPercentile(95));
        log.info("P99            : {} ns",  h.getValueAtPercentile(99));
        log.info("P99.9          : {} ns",  h.getValueAtPercentile(99.9));
        log.info("P99.99         : {} ns",  h.getValueAtPercentile(99.99));
        log.info("Max            : {} ns",  h.getMaxValue());
        // Phase 1 验证目标
        final long p99 = h.getValueAtPercentile(99);
        if (p99 < 1000) {
            log.info("PASS: P99 latency {} ns < 1000 ns (1 us). Phase 1 target achieved!", p99);
        } else {
            log.warn("WARN: P99 latency {} ns >= 1000 ns. Consider running on Linux bare-metal.", p99);
        }
    }
}
```

### 7.2 运行完整链路 Demo

```bash
mkdir -p /tmp/aeron-full-demo

mvn exec:java -pl matching-engine \
  -Dexec.mainClass="com.trading.matching.demo.FullPipelineDemo" \
  "-Daeron.dir=/tmp/aeron-full-demo" \
  -Dexec.jvmArgs="-Xms512m -Xmx512m --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED"
```

---

## 8. 延迟基准测试

### 8.1 SBE 编解码微基准（JMH）

文件：`benchmark/src/main/java/com/trading/benchmark/SbeCodecBenchmark.java`

```java
package com.trading.benchmark;

import com.trading.sbe.*;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * SBE 编解码性能基准测试。
 *
 * 验证目标：SBE 编解码单条消息 < 50ns。
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
```

### 8.2 对象池微基准

文件：`benchmark/src/main/java/com/trading/benchmark/ObjectPoolBenchmark.java`

```java
package com.trading.benchmark;

import com.trading.util.ObjectPool;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

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

    static class TestObject {
        long field1, field2, field3, field4;

        void reset(long a, long b) {
            this.field1 = a;
            this.field2 = b;
        }
    }

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
}
```

### 8.3 运行基准测试

```bash
cd trading-platform
mvn package -pl benchmark -am -q

# 运行 SBE 基准
java --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     --add-opens java.base/java.lang=ALL-UNNAMED \
     -jar benchmark/target/benchmarks.jar "SbeCodecBenchmark" \
     -rf json -rff results-sbe.json

# 运行对象池基准
java -jar benchmark/target/benchmarks.jar "ObjectPoolBenchmark" \
     -rf json -rff results-pool.json
```

**期望结果（参考值）：**

```
Benchmark                   Mode  Cnt   Score   Error  Units
SbeCodecBenchmark.encode    avgt    5   28.34 ± 0.82  ns/op
SbeCodecBenchmark.decode    avgt    5   12.15 ± 0.33  ns/op
ObjectPoolBenchmark.withPool avgt   5    8.22 ± 0.15  ns/op
ObjectPoolBenchmark.withNew  avgt   5   45.67 ± 2.11  ns/op  (慢 5x，且产生 GC)
```

---

## 9. 常见问题排查

### 9.1 `java.lang.UnsatisfiedLinkError: /tmp/aeron-...`

**原因：** Agrona 尝试解压 native 库到临时目录失败（权限问题）。

```bash
# 解决：确保 /tmp 可写，或指定 tmpdir
-Djava.io.tmpdir=/your/writable/tmp
```

### 9.2 `ERROR StatusMessageFlyweight - channel error`

**原因：** Aeron Media Driver 已在运行，目录锁冲突。

```bash
# 解决：清理 aeron 目录（或在代码中设置 dirDeleteOnStart=true）
rm -rf /tmp/aeron-demo
mkdir -p /tmp/aeron-demo
```

### 9.3 `Publication.offer() 一直返回 BACK_PRESSURED`

**原因：** Subscriber 消费速度低于 Publisher 发布速度，RingBuffer 被填满。

**排查步骤：**
1. 检查 Subscriber 是否正确启动
2. 检查 Subscriber 的 FragmentHandler 是否有阻塞操作
3. 检查 RingBuffer 大小是否过小

```java
// 添加诊断日志
if (result == Publication.BACK_PRESSURED) {
    log.warn("Back pressured! Check subscriber throughput.");
}
```

### 9.4 Maven SBE 代码生成失败（`SbeTool` 找不到 Schema）

**排查：**

```bash
# 检查 Schema 文件是否存在
ls common/common-sbe/src/main/resources/sbe/

# 手动运行 SBE 工具检查 Schema 合法性
mvn generate-sources -pl common/common-sbe -X 2>&1 | grep -i "sbe\|error"
```

### 9.5 macOS 上延迟比 Linux 高 10 倍

**原因：** macOS 没有 `/dev/shm`，Aeron IPC 使用文件系统而非真正的共享内存；macOS 内核调度精度也不如 Linux。

**说明：** Phase 1 在 macOS 上开发是可以的，P99 延迟在 2~10μs 级别属于正常。生产环境需要在 Linux 裸机上运行（隔离 CPU 核心），才能达到 < 1μs 目标。

### 9.6 `--add-opens` 相关警告/错误

**原因：** Aeron 需要访问 Java 内部 API（`sun.nio.ch`）。

**在 Maven 中统一配置：** 确保根 `pom.xml` 的 `maven-surefire-plugin` 和 `maven-compiler-plugin` 都包含了 `--add-opens` 参数（见第 2.2 节）。

**在 IDE 中：** 在 Run/Debug Configuration 的 VM options 中添加：

```
--add-opens java.base/sun.nio.ch=ALL-UNNAMED
--add-opens java.base/java.lang=ALL-UNNAMED
```

---

## Phase 1 完成检查清单

完成 Phase 1 后，验证以下所有项目：

- [ ] `mvn validate` 通过，无报错
- [ ] SBE Schema 生成器正确生成所有编解码类
- [ ] `SnowflakeIdGeneratorTest` 全部通过（唯一性、单调性）
- [ ] `PriceUtilTest` 全部通过（精度转换正确）
- [ ] `AeronIpcDemo` 运行成功，吞吐量 > 1M msg/sec
- [ ] `DisruptorPipelineDemo` 运行成功，P99 < 10μs（macOS），< 1μs（Linux 物理机）
- [ ] `FullPipelineDemo` 运行成功，端到端 P99 < 10μs（macOS）
- [ ] JMH 基准：SBE encode < 50ns，SBE decode < 30ns
- [ ] JMH 基准：ObjectPool.borrow+release < 20ns

---

## 下一步：Phase 2

Phase 1 完成后，进入 **Phase 2：撮合引擎核心实现**，包括：

1. `OrderBook` 真实数据结构实现（`LongTreeMap` + 链表 + 对象池）
2. `Limit/Market/IOC/FOK/PostOnly` 完整撮合算法
3. 撮合 Disruptor Pipeline 与 Aeron IPC 真实集成
4. 单元测试：覆盖所有撮合 edge case
5. 性能基准：单交易对 > 500K orders/sec
