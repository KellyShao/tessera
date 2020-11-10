package com.quorum.tessera.config;

import org.junit.After;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class ConfigHolderTest {

    @After
    public void afterTest() {
        ConfigHolder.INSTANCE.setConfig(null);
    }

    @Test
    public void setGetConfig() {
        ConfigHolder hdler = ConfigHolder.INSTANCE;
        Config config = mock(Config.class);

        hdler.setConfig(config);
        assertThat(hdler.getConfig()).isSameAs(config);

    }

}
