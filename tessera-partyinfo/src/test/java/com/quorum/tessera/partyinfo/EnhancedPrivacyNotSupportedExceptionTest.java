package com.quorum.tessera.partyinfo;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class EnhancedPrivacyNotSupportedExceptionTest {

    @Test
    public void createInstance() {

        EnhancedPrivacyNotSupportedException ex = new EnhancedPrivacyNotSupportedException("not supported");

        assertThat(ex).isNotNull();
    }
}
