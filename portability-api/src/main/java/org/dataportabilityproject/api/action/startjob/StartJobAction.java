/*
 * Copyright 2018 The Data Transfer Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.dataportabilityproject.api.action.startjob;

import com.google.api.client.util.Sleeper;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.io.BaseEncoding;
import com.google.inject.Inject;
import org.dataportabilityproject.api.action.Action;
import org.dataportabilityproject.api.action.ActionUtils;
import org.dataportabilityproject.security.AsymmetricKeyGenerator;
import org.dataportabilityproject.security.Encrypter;
import org.dataportabilityproject.security.EncrypterFactory;
import org.dataportabilityproject.security.SymmetricKeyGenerator;
import org.dataportabilityproject.spi.cloud.storage.JobStore;
import org.dataportabilityproject.spi.cloud.types.JobAuthorization;
import org.dataportabilityproject.spi.cloud.types.JobAuthorization.State;
import org.dataportabilityproject.spi.cloud.types.PortabilityJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.security.PublicKey;
import java.util.UUID;

/** An {@link Action} that starts a transfer job. */
public final class StartJobAction implements Action<StartJobActionRequest, StartJobActionResponse> {
  private static final Logger logger = LoggerFactory.getLogger(StartJobAction.class);

  private final JobStore store;
  private final SymmetricKeyGenerator symmetricKeyGenerator;
  private final AsymmetricKeyGenerator asymmetricKeyGenerator;

  @Inject
  StartJobAction(
      JobStore store,
      SymmetricKeyGenerator symmetricKeyGenerator,
      AsymmetricKeyGenerator asymmetricKeyGenerator) {
    this.store = store;
    this.symmetricKeyGenerator = symmetricKeyGenerator;
    this.asymmetricKeyGenerator = asymmetricKeyGenerator;
  }

  /**
   * Starts a job using the following flow:
   * <li>Validate auth data is present in cookies
   * <li>Set Job to state CREDS_AVAILABLE
   * <li>Wait for a transfer worker to be assigned
   * <li>Once transfer worker assigned, grab transfer key to encrypt auth data from cookies
   * <li>Update job with auth data
   */
  @Override
  public StartJobActionResponse handle(StartJobActionRequest request) {
    UUID jobId = request.getJobId();
    // Update the job to indicate to transfer worker processes that creds are available for
    // encryption
    updateStateToCredsAvailable(jobId);

    // Poll and block until a public key is assigned to this job, e.g. from a specific transfer
    // worker
    // instance
    PortabilityJob job = pollForPublicKey(jobId);

    // Update this job with credentials encrypted with a public key, e.g. for a specific transfer
    // worker
    // instance
    encryptAndUpdateJobWithCredentials(
        jobId,
        job,
        request.getEncryptedExportAuthCredential(),
        request.getEncryptedImportAuthCredential());

    return StartJobActionResponse.create(jobId);
  }

  /**
   * Update the job to state to {@code State.CREDS_AVAILABLE} in the store. This indicates to the
   * pool of workers that this job is available for processing.
   */
  private void updateStateToCredsAvailable(UUID jobId) {
    PortabilityJob job = store.findJob(jobId);
    validateJob(job);

    // Set update job auth data
    JobAuthorization jobAuthorization =
        job.jobAuthorization().toBuilder().setState(State.CREDS_AVAILABLE).build();

    job = job.toBuilder().setAndValidateJobAuthorization(jobAuthorization).build();
    try {
      store.updateJob(jobId, job);
      logger.debug("Updated job {} to CREDS_AVAILABLE", jobId);
    } catch (IOException e) {
      throw new RuntimeException("Unable to update job", e);
    }
  }

  /**
   * Polls until the a public key is assigned and persisted with the job. This key will subsquently
   * be used to encrypt credentials.
   */
  private PortabilityJob pollForPublicKey(UUID jobId) {
    // Loop until the transfer worker updates it to assigned without auth data state, e.g. at that
    // point the transfer worker instance key will be populated
    // TODO: start new thread
    // TODO: implement timeout condition
    // TODO: Handle case where API dies while waiting
    PortabilityJob job = store.findJob(jobId);
    while (job == null
        || job.jobAuthorization().state()
            != JobAuthorization.State.CREDS_ENCRYPTION_KEY_GENERATED) {
      logger.debug("Waiting for job {} to enter state CREDS_ENCRYPTION_KEY_GENERATED", jobId);
      try {
        Sleeper.DEFAULT.sleep(10000);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
      job = store.findJob(jobId);
    }

    logger.debug("Got job {} in state CREDS_ENCRYPTION_KEY_GENERATED", jobId);

    // TODO: Consolidate validation with the internal PortabilityJob validation
    Preconditions.checkNotNull(
        job.jobAuthorization().authPublicKey(),
        "Expected job "
            + jobId
            + " to have a transfer worker instance's public key after being assigned "
            + "(state CREDS_ENCRYPTION_KEY_GENERATED)");
    Preconditions.checkState(
        job.jobAuthorization().encryptedExportAuthData() == null,
        "Didn't expect job "
            + jobId
            + " to have encrypted export auth data yet in state "
            + "CREDS_ENCRYPTION_KEY_GENERATED");
    Preconditions.checkState(
        job.jobAuthorization().encryptedImportAuthData() == null,
        "Didn't expect job "
            + jobId
            + " to have encrypted import auth data yet in state "
            + "CREDS_ENCRYPTION_KEY_GENERATED");

    return job;
  }

  // TODO: Consolidate validation with the internal PortabilityJob validation
  private void validateJob(PortabilityJob job) {

    // Validate
    String dataType = job.transferDataType();
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(dataType), "Missing valid dataTypeParam: %s", dataType);

    String exportService = job.exportService();
    Preconditions.checkArgument(
        ActionUtils.isValidExportService(exportService),
        "Missing valid exportService: %s",
        exportService);

    String importService = job.importService();
    Preconditions.checkArgument(
        ActionUtils.isValidImportService(importService),
        "Missing valid importService: %s",
        importService);
  }

  /**
   * Encrypt the export and import credentials with a new {@link SecretKey} and {@link PublicKey}
   * assigned to this job then update the data store to {@code State.CREDS_ENCRYPTED} state.
   */
  private void encryptAndUpdateJobWithCredentials(
      UUID jobId,
      PortabilityJob job,
      String encryptedExportAuthCredential,
      String encryptedImportAuthCredential) {

    // Step 1 - Generate authSecretKey, a new SecretKey which must not be persisted as is.
    SecretKey authSecretKey = symmetricKeyGenerator.generate();

    // Step 2 - Encrypt the auth data with authSecretKey
    Encrypter secretKeyEncrypter = EncrypterFactory.create(authSecretKey);
    String doublyEncryptedExportAuthData =
        secretKeyEncrypter.encrypt(encryptedExportAuthCredential);
    String doublyEncryptedImportAuthData =
        secretKeyEncrypter.encrypt(encryptedImportAuthCredential);

    // Step 3 - Encrypt the authSecretKey itself with the authPublickey
    PublicKey authPublicKey =
        asymmetricKeyGenerator.parse(
            BaseEncoding.base64Url().decode(job.jobAuthorization().authPublicKey()));
    Encrypter asymmetricEncrypter = EncrypterFactory.create(authPublicKey);

    String encryptedAuthSecretKey =
        asymmetricEncrypter.encrypt(BaseEncoding.base64Url().encode(authSecretKey.getEncoded()));

    // Populate job with encrypted auth data
    JobAuthorization updatedJobAuthorization =
        job.jobAuthorization()
            .toBuilder()
            .setEncryptedExportAuthData(doublyEncryptedExportAuthData)
            .setEncryptedImportAuthData(doublyEncryptedImportAuthData)
            .setAuthSecretKey(encryptedAuthSecretKey)
            .setState(JobAuthorization.State.CREDS_ENCRYPTED)
            .build();
    job = job.toBuilder().setAndValidateJobAuthorization(updatedJobAuthorization).build();
    logger.debug("Updating job {} from CREDS_ENCRYPTION_KEY_GENERATED to CREDS_ENCRYPTED", jobId);
    try {
      store.updateJob(jobId, job);
      logger.debug("Updated job {} to CREDS_ENCRYPTED", jobId);
    } catch (IOException e) {
      throw new RuntimeException("Unable to update job", e);
    }
  }
}
