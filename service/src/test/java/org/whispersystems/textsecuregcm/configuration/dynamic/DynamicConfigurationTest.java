/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.configuration.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.vdurmont.semver4j.Semver;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.whispersystems.textsecuregcm.configuration.RateLimitsConfiguration.CardinalityRateLimitConfiguration;
import org.whispersystems.textsecuregcm.storage.DynamicConfigurationManager;
import org.whispersystems.textsecuregcm.util.ua.ClientPlatform;

class DynamicConfigurationTest {

  @Test
  void testParseExperimentConfig() throws JsonProcessingException {
    {
      final String emptyConfigYaml = "test: true";
      final DynamicConfiguration emptyConfig =
          DynamicConfigurationManager.parseConfiguration(emptyConfigYaml, DynamicConfiguration.class).orElseThrow();

      assertFalse(emptyConfig.getExperimentEnrollmentConfiguration("test").isPresent());
    }

    {
      final String experimentConfigYaml = """
          experiments:
            percentageOnly:
              enrollmentPercentage: 12
            uuidsAndPercentage:
              enrolledUuids:
                - 717b1c09-ed0b-4120-bb0e-f4697534b8e1
                - 279f264c-56d7-4bbf-b9da-de718ff90903
              enrollmentPercentage: 77
            uuidsOnly:
              enrolledUuids:
                - 71618739-114c-4b1f-bb0d-6478a44eb600
          """;

      final DynamicConfiguration config =
          DynamicConfigurationManager.parseConfiguration(experimentConfigYaml, DynamicConfiguration.class).orElseThrow();

      assertFalse(config.getExperimentEnrollmentConfiguration("unconfigured").isPresent());

      assertTrue(config.getExperimentEnrollmentConfiguration("percentageOnly").isPresent());
      assertEquals(12, config.getExperimentEnrollmentConfiguration("percentageOnly").get().getEnrollmentPercentage());
      assertEquals(Collections.emptySet(),
          config.getExperimentEnrollmentConfiguration("percentageOnly").get().getEnrolledUuids());

      assertTrue(config.getExperimentEnrollmentConfiguration("uuidsAndPercentage").isPresent());
      assertEquals(77,
          config.getExperimentEnrollmentConfiguration("uuidsAndPercentage").get().getEnrollmentPercentage());
      assertEquals(Set.of(UUID.fromString("717b1c09-ed0b-4120-bb0e-f4697534b8e1"),
          UUID.fromString("279f264c-56d7-4bbf-b9da-de718ff90903")),
          config.getExperimentEnrollmentConfiguration("uuidsAndPercentage").get().getEnrolledUuids());

      assertTrue(config.getExperimentEnrollmentConfiguration("uuidsOnly").isPresent());
      assertEquals(0, config.getExperimentEnrollmentConfiguration("uuidsOnly").get().getEnrollmentPercentage());
      assertEquals(Set.of(UUID.fromString("71618739-114c-4b1f-bb0d-6478a44eb600")),
          config.getExperimentEnrollmentConfiguration("uuidsOnly").get().getEnrolledUuids());
    }
  }

  @Test
  void testParsePreRegistrationExperiments() throws JsonProcessingException {
    {
      final String emptyConfigYaml = "test: true";
      final DynamicConfiguration emptyConfig =
          DynamicConfigurationManager.parseConfiguration(emptyConfigYaml, DynamicConfiguration.class).orElseThrow();

      assertFalse(emptyConfig.getPreRegistrationEnrollmentConfiguration("test").isPresent());
    }

    {
      final String experimentConfigYaml = """
          preRegistrationExperiments:
            percentageOnly:
              enrollmentPercentage: 17
            e164sCountryCodesAndPercentage:
              enrolledE164s:
                - +120255551212
                - +3655323174
              excludedE164s:
                - +120255551213
                - +3655323175
              enrollmentPercentage: 46
              excludedCountryCodes:
                - 47
              includedCountryCodes:
                - 56
            e164sAndExcludedCodes:
              enrolledE164s:
                - +120255551212
              excludedCountryCodes:
                - 47
          """;

      final DynamicConfiguration config =
          DynamicConfigurationManager.parseConfiguration(experimentConfigYaml, DynamicConfiguration.class).orElseThrow();

      assertFalse(config.getPreRegistrationEnrollmentConfiguration("unconfigured").isPresent());

      {
        final Optional<DynamicPreRegistrationExperimentEnrollmentConfiguration> percentageOnly = config
            .getPreRegistrationEnrollmentConfiguration("percentageOnly");
        assertTrue(percentageOnly.isPresent());
        assertEquals(17,
            percentageOnly.get().getEnrollmentPercentage());
        assertEquals(Collections.emptySet(),
            percentageOnly.get().getEnrolledE164s());
        assertEquals(Collections.emptySet(),
            percentageOnly.get().getExcludedE164s());
      }

      {
        final Optional<DynamicPreRegistrationExperimentEnrollmentConfiguration> e164sCountryCodesAndPercentage = config
            .getPreRegistrationEnrollmentConfiguration("e164sCountryCodesAndPercentage");

        assertTrue(e164sCountryCodesAndPercentage.isPresent());
        assertEquals(46,
            e164sCountryCodesAndPercentage.get().getEnrollmentPercentage());
        assertEquals(Set.of("+120255551212", "+3655323174"),
            e164sCountryCodesAndPercentage.get().getEnrolledE164s());
        assertEquals(Set.of("+120255551213", "+3655323175"),
            e164sCountryCodesAndPercentage.get().getExcludedE164s());
        assertEquals(Set.of("47"),
            e164sCountryCodesAndPercentage.get().getExcludedCountryCodes());
        assertEquals(Set.of("56"),
            e164sCountryCodesAndPercentage.get().getIncludedCountryCodes());
      }

      {
        final Optional<DynamicPreRegistrationExperimentEnrollmentConfiguration> e164sAndExcludedCodes = config
            .getPreRegistrationEnrollmentConfiguration("e164sAndExcludedCodes");
        assertTrue(e164sAndExcludedCodes.isPresent());
        assertEquals(0, e164sAndExcludedCodes.get().getEnrollmentPercentage());
        assertEquals(Set.of("+120255551212"),
            e164sAndExcludedCodes.get().getEnrolledE164s());
        assertTrue(e164sAndExcludedCodes.get().getExcludedE164s().isEmpty());
        assertEquals(Set.of("47"),
            e164sAndExcludedCodes.get().getExcludedCountryCodes());
      }
    }
  }

  @Test
  void testParseRemoteDeprecationConfig() throws JsonProcessingException {
    {
      final String emptyConfigYaml = "test: true";
      final DynamicConfiguration emptyConfig =
          DynamicConfigurationManager.parseConfiguration(emptyConfigYaml, DynamicConfiguration.class).orElseThrow();

      assertNotNull(emptyConfig.getRemoteDeprecationConfiguration());
    }

    {
      final String remoteDeprecationConfig = """
          remoteDeprecation:
            minimumVersions:
              IOS: 1.2.3
              ANDROID: 4.5.6
            versionsPendingDeprecation:
              DESKTOP: 7.8.9
            blockedVersions:
              DESKTOP:
                - 1.4.0-beta.2
          """;

      final DynamicConfiguration config =
          DynamicConfigurationManager.parseConfiguration(remoteDeprecationConfig, DynamicConfiguration.class).orElseThrow();

      final DynamicRemoteDeprecationConfiguration remoteDeprecationConfiguration = config
          .getRemoteDeprecationConfiguration();

      assertEquals(Map.of(ClientPlatform.IOS, new Semver("1.2.3"), ClientPlatform.ANDROID, new Semver("4.5.6")),
          remoteDeprecationConfiguration.getMinimumVersions());
      assertEquals(Map.of(ClientPlatform.DESKTOP, new Semver("7.8.9")),
          remoteDeprecationConfiguration.getVersionsPendingDeprecation());
      assertEquals(Map.of(ClientPlatform.DESKTOP, Set.of(new Semver("1.4.0-beta.2"))),
          remoteDeprecationConfiguration.getBlockedVersions());
      assertTrue(remoteDeprecationConfiguration.getVersionsPendingBlock().isEmpty());
    }
  }

  @Test
  void testParseMessageRateConfiguration() throws JsonProcessingException {
    {
      final String emptyConfigYaml = "test: true";
      final DynamicConfiguration emptyConfig =
          DynamicConfigurationManager.parseConfiguration(emptyConfigYaml, DynamicConfiguration.class).orElseThrow();

      assertFalse(emptyConfig.getMessageRateConfiguration().isEnforceUnsealedSenderRateLimit());
    }

    {
      final String messageRateConfigYaml = """
          messageRate:
            enforceUnsealedSenderRateLimit: true
          """;

      final DynamicConfiguration emptyConfig =
          DynamicConfigurationManager.parseConfiguration(messageRateConfigYaml, DynamicConfiguration.class).orElseThrow();

      assertTrue(emptyConfig.getMessageRateConfiguration().isEnforceUnsealedSenderRateLimit());
    }
  }

  @Test
  void testParseFeatureFlags() throws JsonProcessingException {
    {
      final String emptyConfigYaml = "test: true";
      final DynamicConfiguration emptyConfig =
          DynamicConfigurationManager.parseConfiguration(emptyConfigYaml, DynamicConfiguration.class).orElseThrow();

      assertTrue(emptyConfig.getActiveFeatureFlags().isEmpty());
    }

    {
      final String featureFlagYaml = """
          featureFlags:
            - testFlag
          """;

      final DynamicConfiguration emptyConfig =
          DynamicConfigurationManager.parseConfiguration(featureFlagYaml, DynamicConfiguration.class).orElseThrow();

      assertTrue(emptyConfig.getActiveFeatureFlags().contains("testFlag"));
    }
  }

  @Test
  void testParseTwilioConfiguration() throws JsonProcessingException {
    {
      final String emptyConfigYaml = "test: true";
      final DynamicConfiguration emptyConfig =
          DynamicConfigurationManager.parseConfiguration(emptyConfigYaml, DynamicConfiguration.class).orElseThrow();

      assertTrue(emptyConfig.getTwilioConfiguration().getNumbers().isEmpty());
    }

    {
      final String twilioConfigYaml = """
          twilio:
            numbers:
              - 2135551212
              - 2135551313
          """;

      final DynamicTwilioConfiguration config =
          DynamicConfigurationManager.parseConfiguration(twilioConfigYaml, DynamicConfiguration.class).orElseThrow()
              .getTwilioConfiguration();

      assertEquals(List.of("2135551212", "2135551313"), config.getNumbers());
    }
  }

  @Test
  void testParsePaymentsConfiguration() throws JsonProcessingException {
    {
      final String emptyConfigYaml = "test: true";
      final DynamicConfiguration emptyConfig =
          DynamicConfigurationManager.parseConfiguration(emptyConfigYaml, DynamicConfiguration.class).orElseThrow();

      assertTrue(emptyConfig.getPaymentsConfiguration().getDisallowedPrefixes().isEmpty());
    }

    {
      final String paymentsConfigYaml = """
          payments:
            disallowedPrefixes:
              - +44
          """;

      final DynamicPaymentsConfiguration config =
          DynamicConfigurationManager.parseConfiguration(paymentsConfigYaml, DynamicConfiguration.class).orElseThrow()
              .getPaymentsConfiguration();

      assertEquals(List.of("+44"), config.getDisallowedPrefixes());
    }
  }

  @Test
  void testParseSignupCaptchaConfiguration() throws JsonProcessingException {
    {
      final String emptyConfigYaml = "test: true";
      final DynamicConfiguration emptyConfig =
          DynamicConfigurationManager.parseConfiguration(emptyConfigYaml, DynamicConfiguration.class).orElseThrow();

      assertTrue(emptyConfig.getSignupCaptchaConfiguration().getCountryCodes().isEmpty());
    }

    {
      final String signupCaptchaConfig = """
          signupCaptcha:
            countryCodes:
              - 1
          """;

      final DynamicSignupCaptchaConfiguration config =
          DynamicConfigurationManager.parseConfiguration(signupCaptchaConfig, DynamicConfiguration.class).orElseThrow()
              .getSignupCaptchaConfiguration();

      assertEquals(Set.of("1"), config.getCountryCodes());
    }
  }

  @Test
  void testParseLimits() throws JsonProcessingException {
    {
      final String emptyConfigYaml = "test: true";
      final DynamicConfiguration emptyConfig =
          DynamicConfigurationManager.parseConfiguration(emptyConfigYaml, DynamicConfiguration.class).orElseThrow();

      assertThat(emptyConfig.getLimits().getUnsealedSenderNumber().getMaxCardinality()).isEqualTo(100);
      assertThat(emptyConfig.getLimits().getUnsealedSenderNumber().getTtl()).isEqualTo(Duration.ofDays(1));
    }

    {
      final String limitsConfig = """
          limits:
            unsealedSenderNumber:
              maxCardinality: 99
              ttl: PT23H
          """;

      final CardinalityRateLimitConfiguration unsealedSenderNumber =
          DynamicConfigurationManager.parseConfiguration(limitsConfig, DynamicConfiguration.class).orElseThrow()
              .getLimits().getUnsealedSenderNumber();

      assertThat(unsealedSenderNumber.getMaxCardinality()).isEqualTo(99);
      assertThat(unsealedSenderNumber.getTtl()).isEqualTo(Duration.ofHours(23));
    }
  }

  @Test
  void testParseRateLimitReset() throws JsonProcessingException {
    {
      final String emptyConfigYaml = "test: true";
      final DynamicConfiguration emptyConfig =
          DynamicConfigurationManager.parseConfiguration(emptyConfigYaml, DynamicConfiguration.class).orElseThrow();

      assertThat(emptyConfig.getRateLimitChallengeConfiguration().getClientSupportedVersions()).isEmpty();
      assertThat(emptyConfig.getRateLimitChallengeConfiguration().isUnsealedSenderLimitEnforced()).isFalse();
    }

    {
      final String rateLimitChallengeConfig = """
          rateLimitChallenge:
            unsealedSenderLimitEnforced: true
            clientSupportedVersions:
              IOS: 5.1.0
              ANDROID: 5.2.0
              DESKTOP: 5.0.0
          """;

      DynamicRateLimitChallengeConfiguration rateLimitChallengeConfiguration =
          DynamicConfigurationManager.parseConfiguration(rateLimitChallengeConfig, DynamicConfiguration.class).orElseThrow()
              .getRateLimitChallengeConfiguration();

      final Map<ClientPlatform, Semver> clientSupportedVersions = rateLimitChallengeConfiguration.getClientSupportedVersions();

      assertThat(clientSupportedVersions.get(ClientPlatform.IOS)).isEqualTo(new Semver("5.1.0"));
      assertThat(clientSupportedVersions.get(ClientPlatform.ANDROID)).isEqualTo(new Semver("5.2.0"));
      assertThat(clientSupportedVersions.get(ClientPlatform.DESKTOP)).isEqualTo(new Semver("5.0.0"));
      assertThat(rateLimitChallengeConfiguration.isUnsealedSenderLimitEnforced()).isTrue();
    }
  }

  @Test
  void testParseDirectoryReconciler() throws JsonProcessingException {
    {
      final String emptyConfigYaml = "test: true";
      final DynamicConfiguration emptyConfig =
          DynamicConfigurationManager.parseConfiguration(emptyConfigYaml, DynamicConfiguration.class).orElseThrow();

      assertThat(emptyConfig.getDirectoryReconcilerConfiguration().isEnabled()).isTrue();
    }

    {
      final String directoryReconcilerConfig = """
          directoryReconciler:
            enabled: false
          """;

      DynamicDirectoryReconcilerConfiguration directoryReconcilerConfiguration =
          DynamicConfigurationManager.parseConfiguration(directoryReconcilerConfig, DynamicConfiguration.class).orElseThrow()
              .getDirectoryReconcilerConfiguration();

      assertThat(directoryReconcilerConfiguration.isEnabled()).isFalse();
    }
  }
}
