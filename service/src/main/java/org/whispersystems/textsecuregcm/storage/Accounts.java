/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.storage;

import static com.codahale.metrics.MetricRegistry.name;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.util.AttributeValues;
import org.whispersystems.textsecuregcm.util.SystemMapper;
import org.whispersystems.textsecuregcm.util.UUIDUtil;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactionConflictException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

public class Accounts extends AbstractDynamoDbStore {

  // uuid, primary key
  static final String KEY_ACCOUNT_UUID = "U";
  // uuid, attribute on account table, primary key for PNI table
  static final String ATTR_PNI_UUID = "PNI";
  // phone number
  static final String ATTR_ACCOUNT_E164 = "P";
  // account, serialized to JSON
  static final String ATTR_ACCOUNT_DATA = "D";
  // internal version for optimistic locking
  static final String ATTR_VERSION = "V";
  // canonically discoverable
  static final String ATTR_CANONICALLY_DISCOVERABLE = "C";
  // username; string
  static final String ATTR_USERNAME = "N";

  private final DynamoDbClient client;

  private final String phoneNumberConstraintTableName;
  private final String phoneNumberIdentifierConstraintTableName;
  private final String usernamesConstraintTableName;
  private final String accountsTableName;

  private final int scanPageSize;

  private static final Timer CREATE_TIMER = Metrics.timer(name(Accounts.class, "create"));
  private static final Timer CHANGE_NUMBER_TIMER = Metrics.timer(name(Accounts.class, "changeNumber"));
  private static final Timer SET_USERNAME_TIMER = Metrics.timer(name(Accounts.class, "setUsername"));
  private static final Timer CLEAR_USERNAME_TIMER = Metrics.timer(name(Accounts.class, "clearUsername"));
  private static final Timer UPDATE_TIMER = Metrics.timer(name(Accounts.class, "update"));
  private static final Timer GET_BY_NUMBER_TIMER = Metrics.timer(name(Accounts.class, "getByNumber"));
  private static final Timer GET_BY_USERNAME_TIMER = Metrics.timer(name(Accounts.class, "getByUsername"));
  private static final Timer GET_BY_PNI_TIMER = Metrics.timer(name(Accounts.class, "getByPni"));
  private static final Timer GET_BY_UUID_TIMER = Metrics.timer(name(Accounts.class, "getByUuid"));
  private static final Timer GET_ALL_FROM_START_TIMER = Metrics.timer(name(Accounts.class, "getAllFrom"));
  private static final Timer GET_ALL_FROM_OFFSET_TIMER = Metrics.timer(name(Accounts.class, "getAllFromOffset"));
  private static final Timer DELETE_TIMER = Metrics.timer(name(Accounts.class, "delete"));

  private static final Logger log = LoggerFactory.getLogger(Accounts.class);

  public Accounts(DynamoDbClient client, String accountsTableName, String phoneNumberConstraintTableName,
      String phoneNumberIdentifierConstraintTableName, final String usernamesConstraintTableName,
      final int scanPageSize) {

    super(client);

    this.client = client;
    this.phoneNumberConstraintTableName = phoneNumberConstraintTableName;
    this.phoneNumberIdentifierConstraintTableName = phoneNumberIdentifierConstraintTableName;
    this.accountsTableName = accountsTableName;
    this.usernamesConstraintTableName = usernamesConstraintTableName;
    this.scanPageSize = scanPageSize;
  }

  public boolean create(Account account) {
    return CREATE_TIMER.record(() -> {

      try {
        TransactWriteItem phoneNumberConstraintPut = TransactWriteItem.builder()
            .put(
                Put.builder()
                    .tableName(phoneNumberConstraintTableName)
                    .item(Map.of(
                        ATTR_ACCOUNT_E164, AttributeValues.fromString(account.getNumber()),
                        KEY_ACCOUNT_UUID, AttributeValues.fromUUID(account.getUuid())))
                    .conditionExpression(
                        "attribute_not_exists(#number) OR (attribute_exists(#number) AND #uuid = :uuid)")
                    .expressionAttributeNames(
                        Map.of("#uuid", KEY_ACCOUNT_UUID,
                            "#number", ATTR_ACCOUNT_E164))
                    .expressionAttributeValues(
                        Map.of(":uuid", AttributeValues.fromUUID(account.getUuid())))
                    .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                    .build())
            .build();

        TransactWriteItem phoneNumberIdentifierConstraintPut = TransactWriteItem.builder()
            .put(
                Put.builder()
                    .tableName(phoneNumberIdentifierConstraintTableName)
                    .item(Map.of(
                        ATTR_PNI_UUID, AttributeValues.fromUUID(account.getPhoneNumberIdentifier()),
                        KEY_ACCOUNT_UUID, AttributeValues.fromUUID(account.getUuid())))
                    .conditionExpression(
                        "attribute_not_exists(#pni) OR (attribute_exists(#pni) AND #uuid = :uuid)")
                    .expressionAttributeNames(
                        Map.of("#uuid", KEY_ACCOUNT_UUID,
                            "#pni", ATTR_PNI_UUID))
                    .expressionAttributeValues(
                        Map.of(":uuid", AttributeValues.fromUUID(account.getUuid())))
                    .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                    .build())
            .build();

        final Map<String, AttributeValue> item = new HashMap<>(Map.of(
            KEY_ACCOUNT_UUID, AttributeValues.fromUUID(account.getUuid()),
            ATTR_ACCOUNT_E164, AttributeValues.fromString(account.getNumber()),
            ATTR_PNI_UUID, AttributeValues.fromUUID(account.getPhoneNumberIdentifier()),
            ATTR_ACCOUNT_DATA, AttributeValues.fromByteArray(SystemMapper.getMapper().writeValueAsBytes(account)),
            ATTR_VERSION, AttributeValues.fromInt(account.getVersion()),
            ATTR_CANONICALLY_DISCOVERABLE, AttributeValues.fromBool(account.shouldBeVisibleInDirectory())));

        TransactWriteItem accountPut = TransactWriteItem.builder()
            .put(Put.builder()
                .conditionExpression("attribute_not_exists(#number) OR #number = :number")
                .expressionAttributeNames(Map.of("#number", ATTR_ACCOUNT_E164))
                .expressionAttributeValues(Map.of(":number", AttributeValues.fromString(account.getNumber())))
                .tableName(accountsTableName)
                .item(item)
                .build())
            .build();

        final TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
            .transactItems(phoneNumberConstraintPut, phoneNumberIdentifierConstraintPut, accountPut)
            .build();

        try {
          client.transactWriteItems(request);
        } catch (TransactionCanceledException e) {

          final CancellationReason accountCancellationReason = e.cancellationReasons().get(2);

          if ("ConditionalCheckFailed".equals(accountCancellationReason.code())) {
            throw new IllegalArgumentException("account identifier present with different phone number");
          }

          final CancellationReason phoneNumberConstraintCancellationReason = e.cancellationReasons().get(0);
          final CancellationReason phoneNumberIdentifierConstraintCancellationReason = e.cancellationReasons().get(1);

          if ("ConditionalCheckFailed".equals(phoneNumberConstraintCancellationReason.code()) ||
              "ConditionalCheckFailed".equals(phoneNumberIdentifierConstraintCancellationReason.code())) {

            // In theory, both reasons should trip in tandem and either should give us the information we need. Even so,
            // we'll be cautious here and make sure we're choosing a condition check that really failed.
            final CancellationReason reason = "ConditionalCheckFailed".equals(phoneNumberConstraintCancellationReason.code()) ?
                phoneNumberConstraintCancellationReason : phoneNumberIdentifierConstraintCancellationReason;

            ByteBuffer actualAccountUuid = reason.item().get(KEY_ACCOUNT_UUID).b().asByteBuffer();
            account.setUuid(UUIDUtil.fromByteBuffer(actualAccountUuid));

            final Account existingAccount = getByAccountIdentifier(account.getUuid()).orElseThrow();
            account.setNumber(existingAccount.getNumber(), existingAccount.getPhoneNumberIdentifier());
            account.setVersion(existingAccount.getVersion());

            update(account);

            return false;
          }

          if ("TransactionConflict".equals(accountCancellationReason.code())) {
            // this should only happen if two clients manage to make concurrent create() calls
            throw new ContestedOptimisticLockException();
          }

          // this shouldn't happen
          throw new RuntimeException("could not create account: " + extractCancellationReasonCodes(e));
        }
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException(e);
      }

      return true;
    });
  }

  /**
   * Changes the phone number for the given account. The given account's number should be its current, pre-change
   * number. If this method succeeds, the account's number will be changed to the new number and its phone number
   * identifier will be changed to the given phone number identifier. If the update fails for any reason, the account's
   * number and PNI will be unchanged.
   * <p/>
   * This method expects that any accounts with conflicting numbers will have been removed by the time this method is
   * called. This method may fail with an unspecified {@link RuntimeException} if another account with the same number
   * exists in the data store.
   *
   * @param account the account for which to change the phone number
   * @param number the new phone number
   */
  public void changeNumber(final Account account, final String number, final UUID phoneNumberIdentifier) {
    CHANGE_NUMBER_TIMER.record(() -> {
      final String originalNumber = account.getNumber();
      final UUID originalPni = account.getPhoneNumberIdentifier();

      boolean succeeded = false;

      account.setNumber(number, phoneNumberIdentifier);

      try {
        final List<TransactWriteItem> writeItems = new ArrayList<>();

        writeItems.add(TransactWriteItem.builder()
            .delete(Delete.builder()
                .tableName(phoneNumberConstraintTableName)
                .key(Map.of(ATTR_ACCOUNT_E164, AttributeValues.fromString(originalNumber)))
                .build())
            .build());

        writeItems.add(TransactWriteItem.builder()
            .put(Put.builder()
                .tableName(phoneNumberConstraintTableName)
                .item(Map.of(
                    KEY_ACCOUNT_UUID, AttributeValues.fromUUID(account.getUuid()),
                    ATTR_ACCOUNT_E164, AttributeValues.fromString(number)))
                .conditionExpression("attribute_not_exists(#number)")
                .expressionAttributeNames(Map.of("#number", ATTR_ACCOUNT_E164))
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                .build())
            .build());

        writeItems.add(TransactWriteItem.builder()
            .delete(Delete.builder()
                .tableName(phoneNumberIdentifierConstraintTableName)
                .key(Map.of(ATTR_PNI_UUID, AttributeValues.fromUUID(originalPni)))
                .build())
            .build());

        writeItems.add(TransactWriteItem.builder()
            .put(Put.builder()
                .tableName(phoneNumberIdentifierConstraintTableName)
                .item(Map.of(
                    KEY_ACCOUNT_UUID, AttributeValues.fromUUID(account.getUuid()),
                    ATTR_PNI_UUID, AttributeValues.fromUUID(phoneNumberIdentifier)))
                .conditionExpression("attribute_not_exists(#pni)")
                .expressionAttributeNames(Map.of("#pni", ATTR_PNI_UUID))
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                .build())
            .build());

        writeItems.add(
            TransactWriteItem.builder()
                .update(Update.builder()
                    .tableName(accountsTableName)
                    .key(Map.of(KEY_ACCOUNT_UUID, AttributeValues.fromUUID(account.getUuid())))
                    .updateExpression("SET #data = :data, #number = :number, #pni = :pni, #cds = :cds ADD #version :version_increment")
                    .conditionExpression("attribute_exists(#number) AND #version = :version")
                    .expressionAttributeNames(Map.of("#number", ATTR_ACCOUNT_E164,
                        "#data", ATTR_ACCOUNT_DATA,
                        "#cds", ATTR_CANONICALLY_DISCOVERABLE,
                        "#pni", ATTR_PNI_UUID,
                        "#version", ATTR_VERSION))
                    .expressionAttributeValues(Map.of(
                        ":data", AttributeValues.fromByteArray(SystemMapper.getMapper().writeValueAsBytes(account)),
                        ":number", AttributeValues.fromString(number),
                        ":pni", AttributeValues.fromUUID(phoneNumberIdentifier),
                        ":cds", AttributeValues.fromBool(account.shouldBeVisibleInDirectory()),
                        ":version", AttributeValues.fromInt(account.getVersion()),
                        ":version_increment", AttributeValues.fromInt(1)))
                    .build())
                .build());

        final TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
            .transactItems(writeItems)
            .build();

        client.transactWriteItems(request);

        account.setVersion(account.getVersion() + 1);
        succeeded = true;
      } catch (final JsonProcessingException e) {
        throw new IllegalArgumentException(e);
      } finally {
        if (!succeeded) {
          account.setNumber(originalNumber, originalPni);
        }
      }
    });
  }

  public void setUsername(final Account account, final String username)
      throws ContestedOptimisticLockException, UsernameNotAvailableException {
    final long startNanos = System.nanoTime();

    final Optional<String> maybeOriginalUsername = account.getUsername();
    account.setUsername(username);

    boolean succeeded = false;

    try {
      final List<TransactWriteItem> writeItems = new ArrayList<>();

      writeItems.add(TransactWriteItem.builder()
          .put(Put.builder()
              .tableName(usernamesConstraintTableName)
              .item(Map.of(
                  KEY_ACCOUNT_UUID, AttributeValues.fromUUID(account.getUuid()),
                  ATTR_USERNAME, AttributeValues.fromString(username)))
              .conditionExpression("attribute_not_exists(#username)")
              .expressionAttributeNames(Map.of("#username", ATTR_USERNAME))
              .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
              .build())
          .build());

      writeItems.add(
          TransactWriteItem.builder()
              .update(Update.builder()
                  .tableName(accountsTableName)
                  .key(Map.of(KEY_ACCOUNT_UUID, AttributeValues.fromUUID(account.getUuid())))
                  .updateExpression("SET #data = :data, #username = :username ADD #version :version_increment")
                  .conditionExpression("#version = :version")
                  .expressionAttributeNames(Map.of("#data", ATTR_ACCOUNT_DATA,
                      "#username", ATTR_USERNAME,
                      "#version", ATTR_VERSION))
                  .expressionAttributeValues(Map.of(
                      ":data", AttributeValues.fromByteArray(SystemMapper.getMapper().writeValueAsBytes(account)),
                      ":username", AttributeValues.fromString(username),
                      ":version", AttributeValues.fromInt(account.getVersion()),
                      ":version_increment", AttributeValues.fromInt(1)))
                  .build())
              .build());

      maybeOriginalUsername.ifPresent(originalUsername -> writeItems.add(TransactWriteItem.builder()
          .delete(Delete.builder()
              .tableName(usernamesConstraintTableName)
              .key(Map.of(ATTR_USERNAME, AttributeValues.fromString(originalUsername)))
              .build())
          .build()));

      final TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
          .transactItems(writeItems)
          .build();

      client.transactWriteItems(request);

      account.setVersion(account.getVersion() + 1);
      succeeded = true;
    } catch (final JsonProcessingException e) {
      throw new IllegalArgumentException(e);
    } catch (final TransactionCanceledException e) {
      if ("ConditionalCheckFailed".equals(e.cancellationReasons().get(0).code())) {
        throw new UsernameNotAvailableException();
      } else if ("ConditionalCheckFailed".equals(e.cancellationReasons().get(1).code())) {
        throw new ContestedOptimisticLockException();
      }

      throw e;
    } finally {
      if (!succeeded) {
        account.setUsername(maybeOriginalUsername.orElse(null));
      }

      SET_USERNAME_TIMER.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }
  }

  public void clearUsername(Account account) {
    account.getUsername().ifPresent(username -> {
      CLEAR_USERNAME_TIMER.record(() -> {
        account.setUsername(null);

        boolean succeeded = false;

        try {
          final List<TransactWriteItem> writeItems = new ArrayList<>();

          writeItems.add(
              TransactWriteItem.builder()
                  .update(Update.builder()
                      .tableName(accountsTableName)
                      .key(Map.of(KEY_ACCOUNT_UUID, AttributeValues.fromUUID(account.getUuid())))
                      .updateExpression("SET #data = :data REMOVE #username ADD #version :version_increment")
                      .conditionExpression("#version = :version")
                      .expressionAttributeNames(Map.of("#data", ATTR_ACCOUNT_DATA,
                          "#username", ATTR_USERNAME,
                          "#version", ATTR_VERSION))
                      .expressionAttributeValues(Map.of(
                          ":data", AttributeValues.fromByteArray(SystemMapper.getMapper().writeValueAsBytes(account)),
                          ":version", AttributeValues.fromInt(account.getVersion()),
                          ":version_increment", AttributeValues.fromInt(1)))
                      .build())
                  .build());

          writeItems.add(TransactWriteItem.builder()
              .delete(Delete.builder()
                  .tableName(usernamesConstraintTableName)
                  .key(Map.of(ATTR_USERNAME, AttributeValues.fromString(username)))
                  .build())
              .build());

          final TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
              .transactItems(writeItems)
              .build();

          client.transactWriteItems(request);

          account.setVersion(account.getVersion() + 1);
          succeeded = true;
        } catch (final JsonProcessingException e) {
          throw new IllegalArgumentException(e);
        } catch (final TransactionCanceledException e) {
          if ("ConditionalCheckFailed".equals(e.cancellationReasons().get(0).code())) {
            throw new ContestedOptimisticLockException();
          }

          throw e;
        } finally {
          if (!succeeded) {
            account.setUsername(username);
          }
        }
      });
    });
  }

  public void update(Account account) throws ContestedOptimisticLockException {
    UPDATE_TIMER.record(() -> {
      final UpdateItemRequest updateItemRequest;

      try {
        updateItemRequest = UpdateItemRequest.builder()
                .tableName(accountsTableName)
                .key(Map.of(KEY_ACCOUNT_UUID, AttributeValues.fromUUID(account.getUuid())))
                .updateExpression("SET #data = :data, #cds = :cds ADD #version :version_increment")
                .conditionExpression("attribute_exists(#number) AND #version = :version")
                .expressionAttributeNames(Map.of("#number", ATTR_ACCOUNT_E164,
                    "#data", ATTR_ACCOUNT_DATA,
                    "#cds", ATTR_CANONICALLY_DISCOVERABLE,
                    "#version", ATTR_VERSION))
                .expressionAttributeValues(Map.of(
                    ":data", AttributeValues.fromByteArray(SystemMapper.getMapper().writeValueAsBytes(account)),
                    ":cds", AttributeValues.fromBool(account.shouldBeVisibleInDirectory()),
                    ":version", AttributeValues.fromInt(account.getVersion()),
                    ":version_increment", AttributeValues.fromInt(1)))
                .build();
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException(e);
      }

      try {
        final UpdateItemResponse response = client.updateItem(updateItemRequest);
        account.setVersion(AttributeValues.getInt(response.attributes(), "V", account.getVersion() + 1));
      } catch (final TransactionConflictException e) {

        throw new ContestedOptimisticLockException();

      } catch (final ConditionalCheckFailedException e) {
        // the exception doesn't give details about which condition failed,
        // but we can infer it was an optimistic locking failure if the UUID is known
        throw getByAccountIdentifier(account.getUuid()).isPresent() ? new ContestedOptimisticLockException() : e;
      }
    });
  }

  public Optional<Account> getByE164(String number) {
    return GET_BY_NUMBER_TIMER.record(() -> {

      final GetItemResponse response = client.getItem(GetItemRequest.builder()
          .tableName(phoneNumberConstraintTableName)
          .key(Map.of(ATTR_ACCOUNT_E164, AttributeValues.fromString(number)))
          .build());

      return Optional.ofNullable(response.item())
          .map(item -> item.get(KEY_ACCOUNT_UUID))
          .map(this::accountByUuid)
          .map(Accounts::fromItem);
    });
  }

  public Optional<Account> getByUsername(final String username) {
    return GET_BY_USERNAME_TIMER.record(() -> {

      final GetItemResponse response = client.getItem(GetItemRequest.builder()
          .tableName(usernamesConstraintTableName)
          .key(Map.of(ATTR_USERNAME, AttributeValues.fromString(username)))
          .build());

      return Optional.ofNullable(response.item())
          .map(item -> item.get(KEY_ACCOUNT_UUID))
          .map(this::accountByUuid)
          .map(Accounts::fromItem);
    });
  }

  public Optional<Account> getByPhoneNumberIdentifier(final UUID phoneNumberIdentifier) {
    return GET_BY_PNI_TIMER.record(() -> {

      final GetItemResponse response = client.getItem(GetItemRequest.builder()
          .tableName(phoneNumberIdentifierConstraintTableName)
          .key(Map.of(ATTR_PNI_UUID, AttributeValues.fromUUID(phoneNumberIdentifier)))
          .build());

      return Optional.ofNullable(response.item())
          .map(item -> item.get(KEY_ACCOUNT_UUID))
          .map(this::accountByUuid)
          .map(Accounts::fromItem);
    });
  }

  private Map<String, AttributeValue> accountByUuid(AttributeValue uuid) {
    GetItemResponse r = client.getItem(GetItemRequest.builder()
        .tableName(accountsTableName)
        .key(Map.of(KEY_ACCOUNT_UUID, uuid))
        .consistentRead(true)
        .build());
    return r.item().isEmpty() ? null : r.item();
  }

  public Optional<Account> getByAccountIdentifier(UUID uuid) {
    return GET_BY_UUID_TIMER.record(() ->
        Optional.ofNullable(accountByUuid(AttributeValues.fromUUID(uuid)))
            .map(Accounts::fromItem));
  }

  public void delete(UUID uuid) {
    DELETE_TIMER.record(() -> {

      getByAccountIdentifier(uuid).ifPresent(account -> {

        TransactWriteItem phoneNumberDelete = TransactWriteItem.builder()
            .delete(Delete.builder()
                .tableName(phoneNumberConstraintTableName)
                .key(Map.of(ATTR_ACCOUNT_E164, AttributeValues.fromString(account.getNumber())))
                .build())
            .build();

        TransactWriteItem accountDelete = TransactWriteItem.builder()
            .delete(Delete.builder()
                .tableName(accountsTableName)
                .key(Map.of(KEY_ACCOUNT_UUID, AttributeValues.fromUUID(uuid)))
                .build())
            .build();

        final List<TransactWriteItem> transactWriteItems = new ArrayList<>(List.of(phoneNumberDelete, accountDelete));

        transactWriteItems.add(TransactWriteItem.builder()
            .delete(Delete.builder()
                .tableName(phoneNumberIdentifierConstraintTableName)
                .key(Map.of(ATTR_PNI_UUID, AttributeValues.fromUUID(account.getPhoneNumberIdentifier())))
                .build())
            .build());

        account.getUsername().ifPresent(username -> transactWriteItems.add(TransactWriteItem.builder()
            .delete(Delete.builder()
                .tableName(usernamesConstraintTableName)
                .key(Map.of(ATTR_USERNAME, AttributeValues.fromString(username)))
                .build())
            .build()));

        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
            .transactItems(transactWriteItems).build();

        client.transactWriteItems(request);
      });
    });
  }

  public AccountCrawlChunk getAllFrom(final UUID from, final int maxCount) {
    final ScanRequest.Builder scanRequestBuilder = ScanRequest.builder()
        .limit(scanPageSize)
        .exclusiveStartKey(Map.of(KEY_ACCOUNT_UUID, AttributeValues.fromUUID(from)));

    return scanForChunk(scanRequestBuilder, maxCount, GET_ALL_FROM_OFFSET_TIMER);
  }

  public AccountCrawlChunk getAllFromStart(final int maxCount) {
    final ScanRequest.Builder scanRequestBuilder = ScanRequest.builder()
        .limit(scanPageSize);

    return scanForChunk(scanRequestBuilder, maxCount, GET_ALL_FROM_START_TIMER);
  }

  private AccountCrawlChunk scanForChunk(final ScanRequest.Builder scanRequestBuilder, final int maxCount, final Timer timer) {

    scanRequestBuilder.tableName(accountsTableName);

    final List<Account> accounts = timer.record(() -> scan(scanRequestBuilder.build(), maxCount)
        .stream()
        .map(Accounts::fromItem)
        .collect(Collectors.toList()));

    return new AccountCrawlChunk(accounts, accounts.size() > 0 ? accounts.get(accounts.size() - 1).getUuid() : null);
  }

  private static String extractCancellationReasonCodes(final TransactionCanceledException exception) {
    return exception.cancellationReasons().stream()
        .map(CancellationReason::code)
        .collect(Collectors.joining(", "));
  }

  @VisibleForTesting
  static Account fromItem(Map<String, AttributeValue> item) {
    if (!item.containsKey(ATTR_ACCOUNT_DATA) ||
        !item.containsKey(ATTR_ACCOUNT_E164) ||
        // TODO: eventually require ATTR_CANONICALLY_DISCOVERABLE
        !item.containsKey(KEY_ACCOUNT_UUID)) {
      throw new RuntimeException("item missing values");
    }
    try {
      Account account = SystemMapper.getMapper().readValue(item.get(ATTR_ACCOUNT_DATA).b().asByteArray(), Account.class);

      final UUID accountIdentifier = UUIDUtil.fromByteBuffer(item.get(KEY_ACCOUNT_UUID).b().asByteBuffer());
      final UUID phoneNumberIdentifierFromAttribute = AttributeValues.getUUID(item, ATTR_PNI_UUID, null);

      if (account.getPhoneNumberIdentifier() == null || phoneNumberIdentifierFromAttribute == null ||
          !Objects.equals(account.getPhoneNumberIdentifier(), phoneNumberIdentifierFromAttribute)) {

        log.warn("Missing or mismatched PNIs for account {}. From JSON: {}; from attribute: {}",
            accountIdentifier, account.getPhoneNumberIdentifier(), phoneNumberIdentifierFromAttribute);
      }

      account.setNumber(item.get(ATTR_ACCOUNT_E164).s(), phoneNumberIdentifierFromAttribute);
      account.setUuid(accountIdentifier);
      account.setUsername(AttributeValues.getString(item, ATTR_USERNAME, null));
      account.setVersion(Integer.parseInt(item.get(ATTR_VERSION).n()));
      account.setCanonicallyDiscoverable(Optional.ofNullable(item.get(ATTR_CANONICALLY_DISCOVERABLE)).map(av -> av.bool()).orElse(false));

      return account;

    } catch (IOException e) {
      throw new RuntimeException("Could not read stored account data", e);
    }
  }
}
