package com.codfishworks.toxiproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;

import com.lambdaworks.redis.RedisClient;
import com.lambdaworks.redis.RedisCommandTimeoutException;
import com.lambdaworks.redis.api.sync.RedisCommands;
import eu.rekawek.toxiproxy.model.ToxicDirection;

public class ToxiproxyTests {

    // Use a shared network so that containers can talk with each other
    private static Network network = Network.newNetwork();

    private static GenericContainer redis = new GenericContainer("redis:5.0.4")
            .withExposedPorts(6379)
            .withNetwork(network);

    private static ToxiproxyContainer toxiproxy = new ToxiproxyContainer()
            .withNetwork(network);

    @BeforeAll
    public static void startDockerContainers() {
        redis.start();
        toxiproxy.start();
    }

    @Test
    public void canConnectToRedisDirectly() {
        RedisClient client = RedisClient.create("redis://" + redis.getContainerIpAddress() + ":" + redis.getFirstMappedPort());

        RedisCommands<String, String> connect = client.connect().sync();
        connect.set("key", "value");
        String result = connect.get("key");

        assertEquals("value", result);
    }

    @Test
    public void canConnectToRedisViaToxiproxy() {
        ToxiproxyContainer.ContainerProxy proxy = toxiproxy.getProxy(redis, 6379);

        RedisClient client = RedisClient.create("redis://" + proxy.getContainerIpAddress() + ":" + proxy.getProxyPort());

        RedisCommands<String, String> connect = client.connect().sync();
        connect.set("key", "value");
        String result = connect.get("key");

        assertEquals("value", result);
    }

    @Test
    public void toxiproxyCanMessUpCallsToRedis() throws Exception {
        ToxiproxyContainer.ContainerProxy proxy = toxiproxy.getProxy(redis, 6379);

        RedisClient client = RedisClient.create("redis://" + proxy.getContainerIpAddress() + ":" + proxy.getProxyPort());
        client.setDefaultTimeout(500, TimeUnit.MILLISECONDS);

        RedisCommands<String, String> connect = client.connect().sync();
        connect.set("key", "value");

        // Let's add an artificial delay and see what happens...
        proxy.toxics().latency("extra_latency", ToxicDirection.UPSTREAM, 1000);

        Assertions.assertThrows(RedisCommandTimeoutException.class, () -> {
            connect.get("key");
        });
    }

    @AfterAll
    public static void stopDockerContainers() {
        redis.stop();
        toxiproxy.stop();
    }
}
