/*
 * Copyright 2013 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.controllers;

import io.dropwizard.auth.Auth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import org.signal.libsignal.usernames.BaseUsernameException;
import org.whispersystems.textsecuregcm.auth.AccountAndAuthenticatedDeviceHolder;
import org.whispersystems.textsecuregcm.auth.AuthenticatedAccount;
import org.whispersystems.textsecuregcm.auth.ChangesDeviceEnabledState;
import org.whispersystems.textsecuregcm.auth.SaltedTokenHash;
import org.whispersystems.textsecuregcm.auth.TurnToken;
import org.whispersystems.textsecuregcm.auth.TurnTokenGenerator;
import org.whispersystems.textsecuregcm.entities.AccountAttributes;
import org.whispersystems.textsecuregcm.entities.AccountIdentifierResponse;
import org.whispersystems.textsecuregcm.entities.AccountIdentityResponse;
import org.whispersystems.textsecuregcm.entities.ApnRegistrationId;
import org.whispersystems.textsecuregcm.entities.ConfirmUsernameHashRequest;
import org.whispersystems.textsecuregcm.entities.DeviceName;
import org.whispersystems.textsecuregcm.entities.EncryptedUsername;
import org.whispersystems.textsecuregcm.entities.GcmRegistrationId;
import org.whispersystems.textsecuregcm.entities.RegistrationLock;
import org.whispersystems.textsecuregcm.entities.ReserveUsernameHashRequest;
import org.whispersystems.textsecuregcm.entities.ReserveUsernameHashResponse;
import org.whispersystems.textsecuregcm.entities.UsernameHashResponse;
import org.whispersystems.textsecuregcm.entities.UsernameLinkHandle;
import org.whispersystems.textsecuregcm.identity.AciServiceIdentifier;
import org.whispersystems.textsecuregcm.identity.ServiceIdentifier;
import org.whispersystems.textsecuregcm.limits.RateLimitedByIp;
import org.whispersystems.textsecuregcm.limits.RateLimiters;
import org.whispersystems.textsecuregcm.storage.Account;
import org.whispersystems.textsecuregcm.storage.AccountsManager;
import org.whispersystems.textsecuregcm.storage.Device;
import org.whispersystems.textsecuregcm.storage.RegistrationRecoveryPasswordsManager;
import org.whispersystems.textsecuregcm.storage.UsernameHashNotAvailableException;
import org.whispersystems.textsecuregcm.storage.UsernameReservationNotFoundException;
import org.whispersystems.textsecuregcm.util.ExceptionUtils;
import org.whispersystems.textsecuregcm.util.HeaderUtils;
import org.whispersystems.textsecuregcm.util.UsernameHashZkProofVerifier;
import org.whispersystems.textsecuregcm.util.Util;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@Path("/v1/accounts")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Account")
public class AccountController {
  public static final int MAXIMUM_USERNAME_HASHES_LIST_LENGTH = 20;
  public static final int USERNAME_HASH_LENGTH = 32;
  public static final int MAXIMUM_USERNAME_CIPHERTEXT_LENGTH = 128;

  private final AccountsManager accounts;
  private final RateLimiters rateLimiters;
  private final TurnTokenGenerator turnTokenGenerator;
  private final RegistrationRecoveryPasswordsManager registrationRecoveryPasswordsManager;
  private final UsernameHashZkProofVerifier usernameHashZkProofVerifier;

  public AccountController(
      AccountsManager accounts,
      RateLimiters rateLimiters,
      TurnTokenGenerator turnTokenGenerator,
      RegistrationRecoveryPasswordsManager registrationRecoveryPasswordsManager,
      UsernameHashZkProofVerifier usernameHashZkProofVerifier) {
    this.accounts = accounts;
    this.rateLimiters = rateLimiters;
    this.turnTokenGenerator = turnTokenGenerator;
    this.registrationRecoveryPasswordsManager = registrationRecoveryPasswordsManager;
    this.usernameHashZkProofVerifier = usernameHashZkProofVerifier;
  }

  @Deprecated
  @GET
  @Path("/turn/")
  @Produces(MediaType.APPLICATION_JSON)
  public TurnToken getTurnToken(@Auth AuthenticatedAccount auth) throws RateLimitExceededException {
    rateLimiters.getTurnLimiter().validate(auth.getAccount().getUuid());
    return turnTokenGenerator.generate(auth.getAccount().getUuid());
  }

  @PUT
  @Path("/gcm/")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @ChangesDeviceEnabledState
  public void setGcmRegistrationId(@Auth AuthenticatedAccount auth,
      @NotNull @Valid GcmRegistrationId registrationId) {

    final Account account = auth.getAccount();
    final Device device = auth.getAuthenticatedDevice();

    if (Objects.equals(device.getGcmId(), registrationId.gcmRegistrationId())) {
      return;
    }

    accounts.updateDevice(account, device.getId(), d -> {
      d.setApnId(null);
      d.setVoipApnId(null);
      d.setGcmId(registrationId.gcmRegistrationId());
      d.setFetchesMessages(false);
    });
  }

  @DELETE
  @Path("/gcm/")
  @ChangesDeviceEnabledState
  public void deleteGcmRegistrationId(@Auth AuthenticatedAccount auth) {
    Account account = auth.getAccount();
    Device device = auth.getAuthenticatedDevice();

    accounts.updateDevice(account, device.getId(), d -> {
      d.setGcmId(null);
      d.setFetchesMessages(false);
      d.setUserAgent("OWA");
    });
  }

  @PUT
  @Path("/apn/")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @ChangesDeviceEnabledState
  public void setApnRegistrationId(@Auth AuthenticatedAccount auth,
      @NotNull @Valid ApnRegistrationId registrationId) {

    final Account account = auth.getAccount();
    final Device device = auth.getAuthenticatedDevice();

    if (Objects.equals(device.getApnId(), registrationId.apnRegistrationId()) &&
        Objects.equals(device.getVoipApnId(), registrationId.voipRegistrationId())) {

      return;
    }

    accounts.updateDevice(account, device.getId(), d -> {
      d.setApnId(registrationId.apnRegistrationId());
      d.setVoipApnId(registrationId.voipRegistrationId());
      d.setGcmId(null);
      d.setFetchesMessages(false);
    });
  }

  @DELETE
  @Path("/apn/")
  @ChangesDeviceEnabledState
  public void deleteApnRegistrationId(@Auth AuthenticatedAccount auth) {
    Account account = auth.getAccount();
    Device device = auth.getAuthenticatedDevice();

    accounts.updateDevice(account, device.getId(), d -> {
      d.setApnId(null);
      d.setVoipApnId(null);
      d.setFetchesMessages(false);
      if (d.getId() == 1) {
        d.setUserAgent("OWI");
      } else {
        d.setUserAgent("OWP");
      }
    });
  }

  @PUT
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/registration_lock")
  public void setRegistrationLock(@Auth AuthenticatedAccount auth, @NotNull @Valid RegistrationLock accountLock) {
    SaltedTokenHash credentials = SaltedTokenHash.generateFor(accountLock.getRegistrationLock());

    accounts.update(auth.getAccount(),
        a -> a.setRegistrationLock(credentials.hash(), credentials.salt()));
  }

  @DELETE
  @Path("/registration_lock")
  public void removeRegistrationLock(@Auth AuthenticatedAccount auth) {
    accounts.update(auth.getAccount(), a -> a.setRegistrationLock(null, null));
  }

  @PUT
  @Path("/name/")
  public void setName(@Auth AuthenticatedAccount auth, @NotNull @Valid DeviceName deviceName) {
    Account account = auth.getAccount();
    Device device = auth.getAuthenticatedDevice();
    accounts.updateDevice(account, device.getId(), d -> d.setName(deviceName.getDeviceName()));
  }

  @PUT
  @Path("/attributes/")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @ChangesDeviceEnabledState
  public void setAccountAttributes(
      @Auth AuthenticatedAccount auth,
      @HeaderParam(HeaderUtils.X_SIGNAL_AGENT) String userAgent,
      @NotNull @Valid AccountAttributes attributes) {
    final Account account = auth.getAccount();
    final byte deviceId = auth.getAuthenticatedDevice().getId();

    final Account updatedAccount = accounts.update(account, a -> {
      a.getDevice(deviceId).ifPresent(d -> {
        d.setFetchesMessages(attributes.getFetchesMessages());
        d.setName(attributes.getName());
        d.setLastSeen(Util.todayInMillis());
        d.setCapabilities(attributes.getCapabilities());
        d.setUserAgent(userAgent);
      });

      a.setRegistrationLockFromAttributes(attributes);
      a.setUnidentifiedAccessKey(attributes.getUnidentifiedAccessKey());
      a.setUnrestrictedUnidentifiedAccess(attributes.isUnrestrictedUnidentifiedAccess());
      a.setDiscoverableByPhoneNumber(attributes.isDiscoverableByPhoneNumber());
    });

    // if registration recovery password was sent to us, store it (or refresh its expiration)
    attributes.recoveryPassword().ifPresent(registrationRecoveryPassword ->
        registrationRecoveryPasswordsManager.storeForCurrentNumber(updatedAccount.getNumber(), registrationRecoveryPassword));
  }

  @GET
  @Path("/me")
  @Deprecated() // use whoami
  @Produces(MediaType.APPLICATION_JSON)
  public AccountIdentityResponse getMe(@Auth AuthenticatedAccount auth) {
    return buildAccountIdentityResponse(auth);
  }

  @GET
  @Path("/whoami")
  @Produces(MediaType.APPLICATION_JSON)
  public AccountIdentityResponse whoAmI(@Auth AuthenticatedAccount auth) {
    return buildAccountIdentityResponse(auth);
  }

  private AccountIdentityResponse buildAccountIdentityResponse(AccountAndAuthenticatedDeviceHolder auth) {
    return new AccountIdentityResponse(auth.getAccount().getUuid(),
        auth.getAccount().getNumber(),
        auth.getAccount().getPhoneNumberIdentifier(),
        auth.getAccount().getUsernameHash().filter(h -> h.length > 0).orElse(null),
        auth.getAccount().isStorageSupported());
  }

  @DELETE
  @Path("/username_hash")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Delete username hash",
      description = """
          Authenticated endpoint. Deletes previously stored username for the account.
          """
  )
  @ApiResponse(responseCode = "204", description = "Username successfully deleted.", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "401", description = "Account authentication check failed.")
  public CompletableFuture<Response> deleteUsernameHash(@Auth final AuthenticatedAccount auth) {
    return accounts.clearUsernameHash(auth.getAccount())
        .thenApply(Util.ASYNC_EMPTY_RESPONSE);
  }

  @PUT
  @Path("/username_hash/reserve")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Reserve username hash",
      description = """
          Authenticated endpoint. Takes in a list of hashes of potential username hashes, finds one that is not taken,
          and reserves it for the current account.
          """
  )
  @ApiResponse(responseCode = "200", description = "Username hash reserved successfully.", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "401", description = "Account authentication check failed.")
  @ApiResponse(responseCode = "409", description = "All username hashes from the list are taken.")
  @ApiResponse(responseCode = "422", description = "Invalid request format.")
  @ApiResponse(responseCode = "429", description = "Ratelimited.")
  public CompletableFuture<ReserveUsernameHashResponse> reserveUsernameHash(
      @Auth final AuthenticatedAccount auth,
      @NotNull @Valid final ReserveUsernameHashRequest usernameRequest) throws RateLimitExceededException {

    rateLimiters.getUsernameReserveLimiter().validate(auth.getAccount().getUuid());

    for (final byte[] hash : usernameRequest.usernameHashes()) {
      if (hash.length != USERNAME_HASH_LENGTH) {
        throw new WebApplicationException(Response.status(422).build());
      }
    }

    return accounts.reserveUsernameHash(auth.getAccount(), usernameRequest.usernameHashes())
        .thenApply(reservation -> new ReserveUsernameHashResponse(reservation.reservedUsernameHash()))
        .exceptionally(throwable -> {
          if (ExceptionUtils.unwrap(throwable) instanceof UsernameHashNotAvailableException) {
            throw new WebApplicationException(Status.CONFLICT);
          }

          throw ExceptionUtils.wrap(throwable);
        });
  }

  @PUT
  @Path("/username_hash/confirm")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Confirm username hash",
      description = """
          Authenticated endpoint. For a previously reserved username hash, confirm that this username hash is now taken
          by this account.
          """
  )
  @ApiResponse(responseCode = "200", description = "Username hash confirmed successfully.", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "401", description = "Account authentication check failed.")
  @ApiResponse(responseCode = "409", description = "Given username hash doesn't match the reserved one or no reservation found.")
  @ApiResponse(responseCode = "410", description = "Username hash not available (username can't be used).")
  @ApiResponse(responseCode = "422", description = "Invalid request format.")
  @ApiResponse(responseCode = "429", description = "Ratelimited.")
  public CompletableFuture<UsernameHashResponse> confirmUsernameHash(
      @Auth final AuthenticatedAccount auth,
      @NotNull @Valid final ConfirmUsernameHashRequest confirmRequest) {

    try {
      usernameHashZkProofVerifier.verifyProof(confirmRequest.zkProof(), confirmRequest.usernameHash());
    } catch (final BaseUsernameException e) {
      throw new WebApplicationException(Response.status(422).build());
    }

    return rateLimiters.getUsernameSetLimiter().validateAsync(auth.getAccount().getUuid())
        .thenCompose(ignored -> accounts.confirmReservedUsernameHash(
            auth.getAccount(),
            confirmRequest.usernameHash(),
            confirmRequest.encryptedUsername()))
        .thenApply(updatedAccount -> new UsernameHashResponse(updatedAccount.getUsernameHash()
            .orElseThrow(() -> new IllegalStateException("Could not get username after setting")),
            updatedAccount.getUsernameLinkHandle()))
        .exceptionally(throwable -> {
          if (ExceptionUtils.unwrap(throwable) instanceof UsernameReservationNotFoundException) {
            throw new WebApplicationException(Status.CONFLICT);
          }

          if (ExceptionUtils.unwrap(throwable) instanceof UsernameHashNotAvailableException) {
            throw new WebApplicationException(Status.GONE);
          }

          throw ExceptionUtils.wrap(throwable);
        })
        .toCompletableFuture();
  }

  @GET
  @Path("/username_hash/{usernameHash}")
  @Produces(MediaType.APPLICATION_JSON)
  @RateLimitedByIp(RateLimiters.For.USERNAME_LOOKUP)
  @Operation(
      summary = "Lookup username hash",
      description = """
          Forced unauthenticated endpoint. For the given username hash, look up a user ID.
          """
  )
  @ApiResponse(responseCode = "200", description = "Account found for the given username.", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "400", description = "Request must not be authenticated.")
  @ApiResponse(responseCode = "404", description = "Account not found for the given username.")
  public CompletableFuture<AccountIdentifierResponse> lookupUsernameHash(
      @Auth final Optional<AuthenticatedAccount> maybeAuthenticatedAccount,
      @PathParam("usernameHash") final String usernameHash) {

    requireNotAuthenticated(maybeAuthenticatedAccount);
    final byte[] hash;
    try {
      hash = Base64.getUrlDecoder().decode(usernameHash);
    } catch (IllegalArgumentException | AssertionError e) {
      throw new WebApplicationException(Response.status(422).build());
    }

    if (hash.length != USERNAME_HASH_LENGTH) {
      throw new WebApplicationException(Response.status(422).build());
    }

    return accounts.getByUsernameHash(hash).thenApply(maybeAccount -> maybeAccount.map(Account::getUuid)
        .map(AciServiceIdentifier::new)
        .map(AccountIdentifierResponse::new)
        .orElseThrow(() -> new WebApplicationException(Status.NOT_FOUND)));
  }

  @PUT
  @Path("/username_link")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Set username link",
      description = """
          Authenticated endpoint. For the given encrypted username generates a username link handle.
          The username link handle can be used to lookup the encrypted username.
          An account can only have one username link at a time; this endpoint overwrites the previous encrypted username if there was one.
          """
  )
  @ApiResponse(responseCode = "200", description = "Username Link updated successfully.", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "401", description = "Account authentication check failed.")
  @ApiResponse(responseCode = "409", description = "Username is not set for the account.")
  @ApiResponse(responseCode = "422", description = "Invalid request format.")
  @ApiResponse(responseCode = "429", description = "Ratelimited.")
  public UsernameLinkHandle updateUsernameLink(
      @Auth final AuthenticatedAccount auth,
      @NotNull @Valid final EncryptedUsername encryptedUsername) throws RateLimitExceededException {
    // check ratelimiter for username link operations
    rateLimiters.forDescriptor(RateLimiters.For.USERNAME_LINK_OPERATION).validate(auth.getAccount().getUuid());

    final Account account = auth.getAccount();

    // check if username hash is set for the account
    if (account.getUsernameHash().isEmpty()) {
      throw new WebApplicationException(Status.CONFLICT);
    }

    final UUID usernameLinkHandle;
    if (encryptedUsername.keepLinkHandle() && account.getUsernameLinkHandle() != null) {
      usernameLinkHandle = account.getUsernameLinkHandle();
    } else {
      usernameLinkHandle = UUID.randomUUID();
    }
    updateUsernameLink(auth.getAccount(), usernameLinkHandle, encryptedUsername.usernameLinkEncryptedValue());
    return new UsernameLinkHandle(usernameLinkHandle);
  }

  @DELETE
  @Path("/username_link")
  @Operation(
      summary = "Delete username link",
      description = """
          Authenticated endpoint. Deletes username link for the given account: previously store encrypted username is deleted
          and username link handle is deactivated.
          """
  )
  @ApiResponse(responseCode = "204", description = "Username Link successfully deleted.", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "401", description = "Account authentication check failed.")
  @ApiResponse(responseCode = "429", description = "Ratelimited.")
  public void deleteUsernameLink(@Auth final AuthenticatedAccount auth) throws RateLimitExceededException {
    // check ratelimiter for username link operations
    rateLimiters.forDescriptor(RateLimiters.For.USERNAME_LINK_OPERATION).validate(auth.getAccount().getUuid());
    clearUsernameLink(auth.getAccount());
  }

  @GET
  @Path("/username_link/{uuid}")
  @Produces(MediaType.APPLICATION_JSON)
  @RateLimitedByIp(RateLimiters.For.USERNAME_LINK_LOOKUP_PER_IP)
  @Operation(
      summary = "Lookup username link",
      description = """
          Enforced unauthenticated endpoint. For the given username link handle, looks up the database for an associated encrypted username.
          If found, encrypted username is returned, otherwise responds with 404 Not Found.
          """
  )
  @ApiResponse(responseCode = "200", description = "Username link with the given handle was found.", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "400", description = "Request must not be authenticated.")
  @ApiResponse(responseCode = "404", description = "Username link was not found for the given handle.")
  @ApiResponse(responseCode = "422", description = "Invalid request format.")
  @ApiResponse(responseCode = "429", description = "Ratelimited.")
  public CompletableFuture<EncryptedUsername> lookupUsernameLink(
      @Auth final Optional<AuthenticatedAccount> maybeAuthenticatedAccount,
      @PathParam("uuid") final UUID usernameLinkHandle) {

    requireNotAuthenticated(maybeAuthenticatedAccount);

    return accounts.getByUsernameLinkHandle(usernameLinkHandle)
        .thenApply(maybeAccount -> maybeAccount.flatMap(Account::getEncryptedUsername)
            .map(EncryptedUsername::new)
            .orElseThrow(NotFoundException::new));
  }

  @Operation(
      summary = "Check whether an account exists",
      description = """
          Enforced unauthenticated endpoint. Checks whether an account with a given identifier exists.
          """
  )
  @ApiResponse(responseCode = "200", description = "An account with the given identifier was found.", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "400", description = "Request must not be authenticated.")
  @ApiResponse(responseCode = "404", description = "An account was not found for the given identifier.")
  @ApiResponse(responseCode = "422", description = "Invalid request format.")
  @ApiResponse(responseCode = "429", description = "Rate-limited.")
  @HEAD
  @Path("/account/{identifier}")
  @RateLimitedByIp(RateLimiters.For.CHECK_ACCOUNT_EXISTENCE)
  public Response accountExists(
      @Auth final Optional<AuthenticatedAccount> authenticatedAccount,

      @Parameter(description = "An ACI or PNI account identifier to check")
      @PathParam("identifier") final ServiceIdentifier accountIdentifier) {

    // Disallow clients from making authenticated requests to this endpoint
    requireNotAuthenticated(authenticatedAccount);

    final Optional<Account> maybeAccount = accounts.getByServiceIdentifier(accountIdentifier);

    return Response.status(maybeAccount.map(ignored -> Status.OK).orElse(Status.NOT_FOUND)).build();
  }

  @DELETE
  @Path("/me")
  public CompletableFuture<Response> deleteAccount(@Auth AuthenticatedAccount auth) {
    return accounts.delete(auth.getAccount(), AccountsManager.DeletionReason.USER_REQUEST).thenApply(Util.ASYNC_EMPTY_RESPONSE);
  }

  private void clearUsernameLink(final Account account) {
    updateUsernameLink(account, null, null);
  }

  private void updateUsernameLink(
      final Account account,
      @Nullable final UUID usernameLinkHandle,
      @Nullable final byte[] encryptedUsername) {
    if ((encryptedUsername == null) ^ (usernameLinkHandle == null)) {
      throw new IllegalStateException("Both or neither arguments must be null");
    }
    accounts.update(account, a -> a.setUsernameLinkDetails(usernameLinkHandle, encryptedUsername));
  }

  private void requireNotAuthenticated(final Optional<AuthenticatedAccount> authenticatedAccount) {
    if (authenticatedAccount.isPresent()) {
      throw new BadRequestException("Operation requires unauthenticated access");
    }
  }
}
