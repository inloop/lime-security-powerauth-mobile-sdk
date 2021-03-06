/*
 * Copyright 2017 Lime - HighTech Solutions s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.getlime.security.powerauth.sdk;

import android.app.FragmentManager;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;
import android.support.annotation.CheckResult;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.util.Base64;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.HashMap;
import java.util.Map;

import io.getlime.security.powerauth.core.ActivationStatus;
import io.getlime.security.powerauth.core.ActivationStep1Param;
import io.getlime.security.powerauth.core.ActivationStep1Result;
import io.getlime.security.powerauth.core.ActivationStep2Param;
import io.getlime.security.powerauth.core.ActivationStep2Result;
import io.getlime.security.powerauth.core.ErrorCode;
import io.getlime.security.powerauth.core.Password;
import io.getlime.security.powerauth.core.Session;
import io.getlime.security.powerauth.core.SessionSetup;
import io.getlime.security.powerauth.core.SignatureFactor;
import io.getlime.security.powerauth.core.SignatureResult;
import io.getlime.security.powerauth.core.SignatureUnlockKeys;
import io.getlime.security.powerauth.e2ee.PA2EncryptionFailedException;
import io.getlime.security.powerauth.e2ee.PA2EncryptorFactory;
import io.getlime.security.powerauth.e2ee.PA2RequestResponseNonPersonalizedEncryptor;
import io.getlime.security.powerauth.exception.PowerAuthErrorCodes;
import io.getlime.security.powerauth.exception.PowerAuthErrorException;
import io.getlime.security.powerauth.exception.PowerAuthMissingConfigException;
import io.getlime.security.powerauth.keychain.PA2Keychain;
import io.getlime.security.powerauth.keychain.fingerprint.FingerprintAuthenticationDialogFragment;
import io.getlime.security.powerauth.keychain.fingerprint.FingerprintKeystore;
import io.getlime.security.powerauth.keychain.fingerprint.ICommitActivationWithFingerprintListener;
import io.getlime.security.powerauth.keychain.fingerprint.IFingerprintActionHandler;
import io.getlime.security.powerauth.networking.client.PA2Client;
import io.getlime.security.powerauth.networking.endpoints.PA2RemoveActivationEndpoint;
import io.getlime.security.powerauth.networking.endpoints.PA2VaultUnlockEndpoint;
import io.getlime.security.powerauth.networking.interfaces.INetworkResponseListener;
import io.getlime.security.powerauth.networking.response.IDataSignatureListener;
import io.getlime.security.powerauth.rest.api.model.base.PowerAuthApiRequest;
import io.getlime.security.powerauth.rest.api.model.base.PowerAuthApiResponse;
import io.getlime.security.powerauth.rest.api.model.entity.NonPersonalizedEncryptedPayloadModel;
import io.getlime.security.powerauth.rest.api.model.request.ActivationCreateCustomRequest;
import io.getlime.security.powerauth.rest.api.model.request.ActivationCreateRequest;
import io.getlime.security.powerauth.rest.api.model.request.ActivationStatusRequest;
import io.getlime.security.powerauth.rest.api.model.response.ActivationCreateCustomResponse;
import io.getlime.security.powerauth.rest.api.model.response.ActivationCreateResponse;
import io.getlime.security.powerauth.rest.api.model.response.ActivationStatusResponse;
import io.getlime.security.powerauth.rest.api.model.response.VaultUnlockResponse;
import io.getlime.security.powerauth.networking.response.IActivationRemoveListener;
import io.getlime.security.powerauth.networking.response.IActivationStatusListener;
import io.getlime.security.powerauth.networking.response.IAddBiometryFactorListener;
import io.getlime.security.powerauth.networking.response.IChangePasswordListener;
import io.getlime.security.powerauth.networking.response.ICreateActivationListener;
import io.getlime.security.powerauth.networking.response.IFetchEncryptionKeyListener;
import io.getlime.security.powerauth.networking.response.ISavePowerAuthStateListener;
import io.getlime.security.powerauth.networking.response.IValidatePasswordListener;
import io.getlime.security.powerauth.sdk.impl.DefaultSavePowerAuthStateListener;
import io.getlime.security.powerauth.sdk.impl.PowerAuthAuthorizationHttpHeader;
import io.getlime.security.powerauth.util.otp.Otp;
import io.getlime.security.powerauth.util.otp.OtpUtil;

/**
 * Class used for the main interaction with the PowerAuth SDK components.
 *
 * @author Petr Dvorak, petr@lime-company.eu
 */
public class PowerAuthSDK {

    private Session mSession;
    private PowerAuthConfiguration mConfiguration;
    private PowerAuthClientConfiguration mClientConfiguration;
    private PowerAuthKeychainConfiguration mKeychainConfiguration;
    private PA2Client mClient;
    private ISavePowerAuthStateListener mStateListener;
    private PA2EncryptorFactory mEncryptorFactory;
    private PA2Keychain mStatusKeychain;
    private PA2Keychain mBiometryKeychain;

    /**
     * Helper class for building new instances.
     */
    public static class Builder {

        private PowerAuthConfiguration mConfiguration;
        private PowerAuthClientConfiguration mClientConfiguration;
        private PowerAuthKeychainConfiguration mKeychainConfiguration;
        private ISavePowerAuthStateListener mStateListener;

        public Builder(@NonNull PowerAuthConfiguration mConfiguration) {
            this.mConfiguration = mConfiguration;
        }

        public Builder clientConfiguration(PowerAuthClientConfiguration configuration) {
            this.mClientConfiguration = configuration;
            return this;
        }

        public Builder keychainConfiguration(PowerAuthKeychainConfiguration configuration) {
            this.mKeychainConfiguration = configuration;
            return this;
        }

        public Builder stateListener(ISavePowerAuthStateListener stateListener) {
            this.mStateListener = stateListener;
            return this;
        }

        public PowerAuthSDK build(@NonNull final Context context) {
            PowerAuthSDK instance = new PowerAuthSDK();
            instance.mConfiguration = mConfiguration;

            if (mKeychainConfiguration != null) {
                instance.mKeychainConfiguration = mKeychainConfiguration;
            } else {
                instance.mKeychainConfiguration = new PowerAuthKeychainConfiguration();
            }

            if (mClientConfiguration != null) {
                instance.mClientConfiguration = mClientConfiguration;
            } else {
                instance.mClientConfiguration = new PowerAuthClientConfiguration.Builder().build();
            }
            instance.mClient = new PA2Client();

            instance.mStatusKeychain = new PA2Keychain(instance.mKeychainConfiguration.getKeychainStatusId());
            instance.mBiometryKeychain = new PA2Keychain(instance.mKeychainConfiguration.getKeychainBiometryId());

            if (mStateListener != null) {
                instance.mStateListener = mStateListener;
            } else {
                instance.mStateListener = new DefaultSavePowerAuthStateListener(context, instance.mStatusKeychain);
            }

            final SessionSetup sessionSetup = new SessionSetup(
                    mConfiguration.getAppKey(),
                    mConfiguration.getAppSecret(),
                    mConfiguration.getMasterServerPublicKey(),
                    0,
                    mConfiguration.getExternalEncryptionKey()
            );

            instance.mSession = new Session(sessionSetup);
            instance.mEncryptorFactory = new PA2EncryptorFactory(instance.mSession);

            boolean b = instance.restoreState(instance.mStateListener.serializedState(instance.mConfiguration.getInstanceId()));

            return instance;
        }

    }

    private PowerAuthSDK() {
    }

    private void throwInvalidConfigurationException() {
        throw new PowerAuthMissingConfigException("Invalid PowerAuthSDK configuration. You must set a valid PowerAuthConfiguration to PowerAuthSDK instance using initializer.");
    }

    @CheckResult
    private @Nullable ActivationStep1Param paramStep1WithActivationCode(@NonNull String activationCode) {
        Otp otp = OtpUtil.parseFromActivationCode(activationCode);
        if (otp == null) {
            return null;
        } else {
            return new ActivationStep1Param(
                    otp.getActivationIdShort(),
                    otp.getActivationOtp(),
                    otp.getActivationSignature()
            );
        }
    }

    /**
     * Return a default device related key used for computing the possession factor encryption key.
     * @param context Context.
     * @return Default device related key.
     */
    private byte[] deviceRelatedKey(@NonNull Context context) {
        return mSession.normalizeSignatureUnlockKeyFromData(mConfiguration.getFetchKeysStrategy().getPossessionUnlockKey(context).getBytes());
    }

    private SignatureUnlockKeys signatureKeysForAuthentication(Context context, PowerAuthAuthentication authentication) {
        // Generate signature key encryption keys
        byte[] possessionKey = null;
        byte[] biometryKey = null;
        Password knowledgeKey = null;

        if (authentication.usePossession) {
            if (authentication.overridenPossessionKey != null) {
                possessionKey = authentication.overridenPossessionKey;
            } else {
                possessionKey = deviceRelatedKey(context);
            }
        }

        if (authentication.useBiometry != null) {
            biometryKey = authentication.useBiometry;
        }

        if (authentication.usePassword != null) {
            knowledgeKey = new Password(authentication.usePassword);
        }

        // Prepare signature unlock keys structure
        return new SignatureUnlockKeys(possessionKey, biometryKey, knowledgeKey);
    }

    private int determineSignatureFactorForAuthentication(PowerAuthAuthentication authentication) {
        if (authentication.usePossession && authentication.usePassword == null && authentication.useBiometry == null) {
            return SignatureFactor.Possession;
        }
        if (!authentication.usePossession && authentication.usePassword != null && authentication.useBiometry == null) {
            return SignatureFactor.Knowledge;
        }
        if (!authentication.usePossession && authentication.usePassword == null && authentication.useBiometry != null) {
            return SignatureFactor.Biometry;
        }
        if (authentication.usePossession && authentication.usePassword != null && authentication.useBiometry == null) {
            return SignatureFactor.Possession_Knowledge;
        }
        if (authentication.usePossession && authentication.usePassword == null && authentication.useBiometry != null) {
            return SignatureFactor.Possession_Biometry;
        }
        if (authentication.usePossession && authentication.usePassword != null && authentication.useBiometry != null) {
            return SignatureFactor.Possession_Knowledge_Biometry;
        }
        // In case invalid combination was selected (no factors, knowledge & biometry), expect the worst...
        return SignatureFactor.Possession_Knowledge_Biometry;
    }

    private int determineSignatureFactorForAuthentication(PowerAuthAuthentication authentication, boolean vaultUnlock) {
        if (vaultUnlock) {
            return determineSignatureFactorForAuthentication(authentication) + SignatureFactor.PrepareForVaultUnlock;
        } else {
            return determineSignatureFactorForAuthentication(authentication);
        }
    }

    private interface IFetchEncryptedVaultUnlockKeyListener {
        void onFetchEncryptedVaultUnlockKeySucceed(String encryptedEncryptionKey);
        void onFetchEncryptedVaultUnlockKeyFailed(Throwable t);
    }

    @CheckResult
    private @Nullable AsyncTask fetchEncryptedVaultUnlockKey(@NonNull final Context context, @NonNull final PowerAuthAuthentication authentication, @NonNull final IFetchEncryptedVaultUnlockKeyListener listener) {
        // Check for the session setup
        if (!mSession.hasValidSetup()) {
            throwInvalidConfigurationException();
        }

        // Check if there is an activation present
        if (!mSession.hasValidActivation() && mSession.hasPendingActivation()) {
            listener.onFetchEncryptedVaultUnlockKeyFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeMissingActivation));
            return null;
        }

        // Compute authorization header based on constants from the specification.
        PowerAuthAuthorizationHttpHeader httpHeader = requestSignatureWithAuthentication(context, authentication, true, "POST", PA2VaultUnlockEndpoint.VAULT_UNLOCK, null);
        if (httpHeader.getPowerAuthErrorCode() == PowerAuthErrorCodes.PA2Succeed) {
            final Map<String, String> headers = new HashMap<>();
            headers.put(httpHeader.getKey(), httpHeader.getValue());

            // Perform the server request
            return mClient.vaultUnlockSignatureHeader(mConfiguration, mClientConfiguration, headers, new INetworkResponseListener<VaultUnlockResponse>() {

                @Override
                public void onNetworkResponse(VaultUnlockResponse vaultUnlockResponse) {
                    // Network communication completed correctly
                    listener.onFetchEncryptedVaultUnlockKeySucceed(vaultUnlockResponse.getEncryptedVaultEncryptionKey());
                }

                @Override
                public void onNetworkError(Throwable t) {
                    // Network error occurred
                    listener.onFetchEncryptedVaultUnlockKeyFailed(t);
                }
            });
        } else {
            listener.onFetchEncryptedVaultUnlockKeyFailed(new PowerAuthErrorException(httpHeader.getPowerAuthErrorCode()));
            return null;
        }
    }

    /**
     * Reference to the low-level Session class.
     * <p>
     * WARNING: This property is exposed only for the purpose of giving developers full low-level control over the cryptographic algorithm and managed activation state.
     * For example, you can call a direct password change method without prior check of the password correctness in cooperation with the server API. Be extremely careful when
     * calling any methods of this instance directly. There are very few protective mechanisms for keeping the session state actually consistent in the functional (not low level)
     * sense. As a result, you may break your activation state (for example, by changing password from incorrect value to some other value).
     */
    public Session getSession() {
        return mSession;
    }

    /**
     * Return the encryptor factory instance, useful for generating custom encryptors.
     *
     * @return Encryptor factory instance.
     */
    public PA2EncryptorFactory getEncryptorFactory() {
        return mEncryptorFactory;
    }

    /**
     * The method is used for saving serialized state of Session, for example after password change method called directly via Session instance. See {@link PowerAuthSDK#getSession()} method.
     */
    public void saveSerializedState() {
        final byte[] state = mSession.serializedState();
        mStateListener.onPowerAuthStateChanged(mConfiguration.getInstanceId(), state);
    }

    /**
     * Restores previously saved PA state.
     *
     * @param state saved PA state.
     * @return TRUE when state restored successfully, otherwise FALSE.
     */
    @CheckResult
    public boolean restoreState(byte[] state) {
        mSession.resetSession();
        final int result = mSession.deserializeState(state);
        return result == ErrorCode.OK;
    }

    /**
     * Checks if the PA library has not been compiled with debug parameters
     *
     * @return Returns TRUE if dynamic library was compiled with a debug features. It is highly recommended
     * to check this boolean and force application to crash, if the production, final app
     * is running against a debug featured library.
     */
    @CheckResult
    public boolean hasDebugFeatures() {
        return mSession.hasDebugFeatures();
    }

    /**
     * Checks if there is a pending activation (activation in progress).
     *
     * @return TRUE if there is a pending activation, FALSE otherwise.
     * @throws PowerAuthMissingConfigException thrown in case configuration is not present.
     */
    @CheckResult
    public boolean hasPendingActivation() {
        // Check for the session setup
        if (mSession == null || !mSession.hasValidSetup()) {
            throwInvalidConfigurationException();
        }
        return mSession.hasPendingActivation();
    }

    /**
     * Checks if there is a valid activation.
     *
     * @return TRUE if there is a valid activation, FALSE otherwise.
     * @throws PowerAuthMissingConfigException thrown in case configuration is not present.
     */
    @CheckResult
    public boolean hasValidActivation() {
        // Check for the session setup
        if (mSession == null || !mSession.hasValidSetup()) {
            throwInvalidConfigurationException();
        }
        return mSession.hasValidActivation();
    }

    /**
     * Reset the PowerAuthSDK instance - remove pending activations and stored error states.
     *
     * @throws PowerAuthMissingConfigException thrown in case configuration is not present.
     */
    public void reset() {
        // Check for the session setup
        if (mSession == null || !mSession.hasValidSetup()) {
            throwInvalidConfigurationException();
        }
        mSession.resetSession();
    }

    /**
     * Destroy the PowerAuthSDK instance. Internal objects will be securely destroyed and PowerAuthSDK instance can't be more used after this call.
     *
     * @throws PowerAuthMissingConfigException thrown in case configuration is not present.
     */
    public void destroy() {
        // Check for the session setup
        if (mSession == null || !mSession.hasValidSetup()) {
            throwInvalidConfigurationException();
        }
        mSession.destroy();
    }

    /**
     * Create a new activation with given name and activation code by calling a PowerAuth 2.0 Standard RESTful API endpoint '/pa/activation/create'.
     *
     * @param name           Activation name, for example "John's phone".
     * @param activationCode Activation code, obtained either via QR code scanning or by manual entry.
     * @param listener       A callback listener called when the process finishes - it contains an activation fingerprint in case of success or error in case of failure.
     * @return AsyncTask associated with the running server request.
     * @throws PowerAuthMissingConfigException thrown in case configuration is not present.
     */
    public @Nullable AsyncTask createActivation(@Nullable String name, @NonNull String activationCode, @NonNull ICreateActivationListener listener) {
        return createActivation(name, activationCode, null, listener);
    }

    /**
     * Create a new activation with given name and activation code by calling a PowerAuth 2.0 Standard RESTful API endpoint '/pa/activation/create'.
     *
     * @param name           Activation name, for example "John's iPhone".
     * @param activationCode Activation code, obtained either via QR code scanning or by manual entry.
     * @param extras         Extra attributes of the activation, used for application specific purposes (for example, info about the client device or system).
     * @param listener       A callback listener called when the process finishes - it contains an activation fingerprint in case of success or error in case of failure.
     * @return AsyncTask associated with the running server request.
     * @throws PowerAuthMissingConfigException thrown in case configuration is not present.
     */
    public @Nullable AsyncTask createActivation(@Nullable String name, @NonNull String activationCode, @Nullable String extras, @NonNull final ICreateActivationListener listener) {
        // Check for the session setup
        if (mSession == null || !mSession.hasValidSetup()) {
            throwInvalidConfigurationException();
        }

        // Check if activation may be started
        if (mSession.hasPendingActivation()) {
            listener.onActivationCreateFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeInvalidActivationState));
            return null;
        }

        mSession.resetSession();

        // Prepare crypto module request
        final ActivationStep1Param paramStep1 = paramStep1WithActivationCode(activationCode);
        if (paramStep1 == null) {
            listener.onActivationCreateFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeInvalidActivationCode));
            return null;
        }

        // Obtain crypto module response
        final ActivationStep1Result step1Result = mSession.startActivation(paramStep1);

        // Perform exchange over PowerAuth 2.0 Standard RESTful API
        final ActivationCreateRequest request = new ActivationCreateRequest();
        request.setActivationIdShort(paramStep1.activationIdShort);
        request.setActivationName(name);
        request.setActivationNonce(step1Result.activationNonce);
        request.setApplicationKey(mConfiguration.getAppKey());
        request.setApplicationSignature(step1Result.applicationSignature);
        request.setEncryptedDevicePublicKey(step1Result.cDevicePublicKey);
        request.setEphemeralPublicKey(step1Result.ephemeralPublicKey);
        request.setExtras(extras);

        // Perform the server request
        return mClient.createActivation(mConfiguration, mClientConfiguration, request, new INetworkResponseListener<ActivationCreateResponse>() {

            @Override
            public void onNetworkResponse(ActivationCreateResponse response) {
                // Network communication completed correctly
                final ActivationStep2Param paramStep2 = new ActivationStep2Param(
                        response.getActivationId(),
                        response.getActivationNonce(),
                        response.getEphemeralPublicKey(),
                        response.getEncryptedServerPublicKey(),
                        response.getEncryptedServerPublicKeySignature());

                // Obtain crypto module response
                final ActivationStep2Result resultStep2 = mSession.validateActivationResponse(paramStep2);
                if (resultStep2.errorCode == ErrorCode.OK) {
                    // Everything was OK
                    listener.onActivationCreateSucceed(resultStep2.hkDevicePublicKey);
                } else {
                    // Error occurred
                    listener.onActivationCreateFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeInvalidActivationData));
                }
            }

            @Override
            public void onNetworkError(Throwable t) {
                // Network error occurred
                listener.onActivationCreateFailed(t);
            }
        });
    }

    public @Nullable AsyncTask createActivation(@Nullable String name, @NonNull Map<String,String> identityAttributes, @NonNull String customSecret, @Nullable String extras, @Nullable Map<String, Object> customAttributes, @NonNull String url, @Nullable Map<String, String> httpHeaders, @NonNull final ICreateActivationListener listener) {

        // Check for the session setup
        if (mSession == null || !mSession.hasValidSetup()) {
            throwInvalidConfigurationException();
        }

        // Check if activation may be started
        if (mSession.hasPendingActivation()) {
            listener.onActivationCreateFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeInvalidActivationState));
            return null;
        }

        mSession.resetSession();

        // Prepare identity attributes token
        byte[] identityAttributesBytes = mSession.prepareKeyValueDictionaryForDataSigning(identityAttributes);
        String identityAttributesString = Base64.encodeToString(identityAttributesBytes, Base64.DEFAULT);

        // Prepare crypto module request
        final ActivationStep1Param paramStep1 = new ActivationStep1Param(identityAttributesString, customSecret, null);

        // Obtain crypto module response
        final ActivationStep1Result resultStep1 = mSession.startActivation(paramStep1);

        // Perform exchange over PowerAuth 2.0 Standard RESTful API
        ActivationCreateRequest powerauth = new ActivationCreateRequest();
        powerauth.setActivationIdShort(paramStep1.activationIdShort);
        powerauth.setActivationName(name);
        powerauth.setActivationNonce(resultStep1.activationNonce);
        powerauth.setApplicationKey(mConfiguration.getAppKey());
        powerauth.setApplicationSignature(resultStep1.applicationSignature);
        powerauth.setEncryptedDevicePublicKey(resultStep1.cDevicePublicKey);
        powerauth.setEphemeralPublicKey(resultStep1.ephemeralPublicKey);
        powerauth.setExtras(extras);

        ActivationCreateCustomRequest request = new ActivationCreateCustomRequest();
        request.setIdentity(identityAttributes);
        request.setCustomAttributes(customAttributes);
        request.setPowerauth(powerauth);

        final Gson gson = new GsonBuilder().create();
        String requestDataString = gson.toJson(request);
        if (requestDataString == null) {
            listener.onActivationCreateFailed(new PA2EncryptionFailedException());
            return null;
        }
        byte[] requestData = requestDataString.getBytes();

        final PA2RequestResponseNonPersonalizedEncryptor encryptor = mEncryptorFactory.buildRequestResponseNonPersonalizedEncryptor();

        PowerAuthApiRequest<NonPersonalizedEncryptedPayloadModel> encryptedRequest = null;
        try {
            encryptedRequest = encryptor.encryptRequestData(requestData);
        } catch (PA2EncryptionFailedException e) {
            listener.onActivationCreateFailed(e);
            return null;
        }

        return mClient.sendNonPersonalizedEncryptedObjectToUrl(mConfiguration, mClientConfiguration, encryptedRequest.getRequestObject(), url, httpHeaders, new INetworkResponseListener<NonPersonalizedEncryptedPayloadModel>() {

            @Override
            public void onNetworkResponse(NonPersonalizedEncryptedPayloadModel nonPersonalizedEncryptedPayloadModel) {
                try {
                    byte[] originalBytes = encryptor.decryptResponse(new PowerAuthApiResponse<>(
                            PowerAuthApiResponse.Status.OK,
                            PowerAuthApiResponse.Encryption.NON_PERSONALIZED,
                            nonPersonalizedEncryptedPayloadModel
                    ));
                    ActivationCreateCustomResponse activationCreateResponse = gson.fromJson(new String(originalBytes), ActivationCreateCustomResponse.class);

                    ActivationStep2Param step2Param = new ActivationStep2Param(
                            activationCreateResponse.getActivationId(),
                            activationCreateResponse.getActivationNonce(),
                            activationCreateResponse.getEphemeralPublicKey(),
                            activationCreateResponse.getEncryptedServerPublicKey(),
                            activationCreateResponse.getEncryptedServerPublicKeySignature()
                    );

                    ActivationStep2Result step2Result = mSession.validateActivationResponse(step2Param);

                    if (step2Result != null && step2Result.errorCode == ErrorCode.OK) {
                        listener.onActivationCreateSucceed(step2Result.hkDevicePublicKey);
                    } else {
                        listener.onActivationCreateFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeInvalidActivationData));
                    }

                } catch (PA2EncryptionFailedException e) {
                    listener.onActivationCreateFailed(e);
                }
            }

            @Override
            public void onNetworkError(Throwable t) {
                listener.onActivationCreateFailed(t);
            }
        });
    }

    public AsyncTask createActivation(String name, Map<String, String> identityAttributes, String url, final ICreateActivationListener listener) {
        return this.createActivation(name, identityAttributes, "00000-00000", null, null, url, null, listener);
    }

    /**
     * Commit activation that was created and store related data using default authentication instance setup with provided password.
     *
     * @param context Context
     * @param password Password to be used for the knowledge related authentication factor.
     * @return int {@link PowerAuthErrorCodes} error code.
     * @throws PowerAuthMissingConfigException thrown in case configuration is not present.
     */
    @CheckResult
    public int commitActivationWithPassword(@NonNull Context context, @NonNull String password) {
        PowerAuthAuthentication authentication = new PowerAuthAuthentication();
        authentication.useBiometry = null;
        authentication.usePossession = true;
        authentication.usePassword = password;
        return commitActivationWithAuthentication(context, authentication);
    }

    /**
     * Commit activation that was created and store related data using default authentication instance setup with provided password and biometry key.
     *
     * @param context Context.
     * @param fragmentManager Fragment manager for the dialog.
     * @param title Dialog title.
     * @param description Dialog description.
     * @param password Password used for activation commit.
     * @param callback Callback with the authentication result.
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    public void commitActivation(final @NonNull Context context, FragmentManager fragmentManager, String title, String description, @NonNull final String password, final ICommitActivationWithFingerprintListener callback) {
        authenticateUsingFingerprint(context, fragmentManager, title, description, true, new IFingerprintActionHandler() {
            @Override
            public void onFingerprintDialogCancelled() {
                callback.onFingerprintDialogCancelled();
            }

            @Override
            public void onFingerprintDialogSuccess(@Nullable byte[] biometricKeyEncrypted) {
                int b = commitActivationWithPassword(context, password, biometricKeyEncrypted);
                callback.onFingerprintDialogSuccess();
            }

            @Override
            public void onFingerprintInfoDialogClosed() {
                callback.onFingerprintDialogCancelled();
            }
        });
    }

    /**
     * Commit activation that was created and store related data using default authentication instance setup with provided password.
     * <p>
     * Calling this method is equivalent to commitActivationWithAuthentication with authentication object set to use all factors and provided password.
     *
     * @param context Context
     * @param password Password to be used for the knowledge related authentication factor.
     * @param encryptedBiometryKey Optional biometry related factor key.
     * @return int {@link PowerAuthErrorCodes} error code.
     * @throws PowerAuthMissingConfigException thrown in case configuration is not present.
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    @CheckResult
    public int commitActivationWithPassword(@NonNull Context context, @NonNull String password, @Nullable byte[] encryptedBiometryKey) {
        PowerAuthAuthentication authentication = new PowerAuthAuthentication();
        authentication.useBiometry = encryptedBiometryKey;
        authentication.usePossession = true;
        authentication.usePassword = password;
        return commitActivationWithAuthentication(context, authentication);
    }

    /**
     * Commit activation that was created and store related data using provided authentication instance.
     *
     * @param authentication An authentication instance specifying what factors should be stored.
     * @return int {@link PowerAuthErrorCodes} error code.
     * @throws PowerAuthMissingConfigException thrown in case configuration is not present.
     */
    @CheckResult
    public int commitActivationWithAuthentication(@NonNull Context context, @NonNull PowerAuthAuthentication authentication) {
        // Check for the session setup
        if (mSession == null || !mSession.hasValidSetup()) {
            throwInvalidConfigurationException();
        }

        // Check if there is a pending activation present and not an already existing valid activation
        if (!mSession.hasPendingActivation() || mSession.hasValidActivation()) {
            return PowerAuthErrorCodes.PA2ErrorCodeInvalidActivationState;
        }

        // Prepare key encryption keys
        final byte[] possessionKey = authentication.usePossession ? deviceRelatedKey(context) : null;
        final byte[] biometryKey = authentication.useBiometry;
        final Password knowledgeKey = authentication.usePassword != null ? new Password(authentication.usePassword) : null;

        // Prepare signature unlock keys structure
        final SignatureUnlockKeys keys = new SignatureUnlockKeys(possessionKey, biometryKey, knowledgeKey);

        // Complete the activation
        final int result = mSession.completeActivation(keys);

        if (result == ErrorCode.OK) {
            // Update state after each successful calculations
            saveSerializedState();

            return PowerAuthErrorCodes.PA2Succeed;
        } else {
            return PowerAuthErrorCodes.PA2ErrorCodeInvalidActivationState;
        }
    }

    /**
     * Fetch the activation status for current activation.
     * <p>
     * If server returns custom object, it is returned in the callback as NSDictionary.
     *
     * @param context  Context
     * @param listener A callback listener with activation status result - it contains status information in case of success and error in case of failure.
     * @return AsyncTask associated with the running server request.
     * @throws PowerAuthMissingConfigException thrown in case configuration is not present.
     */
    public @Nullable AsyncTask fetchActivationStatusWithCallback(@NonNull final Context context, @NonNull final IActivationStatusListener listener) {
        // Check for the session setup
        if (mSession == null || !mSession.hasValidSetup()) {
            throwInvalidConfigurationException();
        }

        // Check if there is an activation present, valid or pending
        if (!mSession.hasValidActivation() && !mSession.hasPendingActivation()) {
            listener.onActivationStatusFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeMissingActivation));
            return null;
        }
        // Handle the case of a pending activation locally.
        // Note that we cannot use the  generic logic here since the transport key is not established yet.
        else if (mSession.hasPendingActivation()) {
            listener.onActivationStatusFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeActivationPending));
            return null;
        }

        ActivationStatusRequest request = new ActivationStatusRequest();
        request.setActivationId(mSession.getActivationIdentifier());

        // Perform the server request
        return mClient.getActivationStatus(mConfiguration, mClientConfiguration, request, new INetworkResponseListener<ActivationStatusResponse>() {

            @Override
            public void onNetworkResponse(ActivationStatusResponse activationStatusResponse) {
                // Network communication completed correctly
                // Prepare unlocking key (possession factor only)
                final SignatureUnlockKeys keys = new SignatureUnlockKeys(deviceRelatedKey(context), null, null);
                // Attempt to decode the activation status
                final ActivationStatus activationStatus = mSession.decodeActivationStatus(activationStatusResponse.getEncryptedStatusBlob(), keys);
                if (activationStatus != null) {
                    // Everything was OK
                    listener.onActivationStatusSucceed(activationStatus);
                } else {
                    // Error occurred when decoding status
                    listener.onActivationStatusFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeInvalidActivationData));
                }
            }

            @Override
            public void onNetworkError(Throwable t) {
                // Network error occurred
                listener.onActivationStatusFailed(t);

            }
        });
    }

    /**
     * Remove current activation by calling a PowerAuth 2.0 Standard RESTful API endpoint '/pa/activation/remove'.
     *
     * @param context        Context.
     * @param authentication An authentication instance specifying what factors should be used to sign the request.
     * @param listener       A callback with activation removal result - in case of an error, an error instance is not 'nil'.
     * @return AsyncTask associated with the running request.
     */
    public @Nullable AsyncTask removeActivationWithAuthentication(@NonNull Context context, @NonNull PowerAuthAuthentication authentication, @NonNull final IActivationRemoveListener listener) {
        // Check for the session setup
        if (mSession == null || !mSession.hasValidSetup()) {
            throwInvalidConfigurationException();
        }

        // Check if there is an activation present
        if (!mSession.hasValidActivation() && mSession.hasPendingActivation()) {
            listener.onActivationRemoveFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeMissingActivation));
            return null;
        }

        PowerAuthAuthorizationHttpHeader httpHeader = requestSignatureWithAuthentication(context, authentication, "POST", PA2RemoveActivationEndpoint.ACTIVATION_REMOVE, null);
        if (httpHeader.getPowerAuthErrorCode() != PowerAuthErrorCodes.PA2Succeed) {
            listener.onActivationRemoveFailed(new PowerAuthErrorException(httpHeader.getPowerAuthErrorCode()));
            return null;
        }

        final Map<String, String> headers = new HashMap<>();
        headers.put(httpHeader.getKey(), httpHeader.getValue());

        return mClient.removeActivationSignatureHeader(mConfiguration, mClientConfiguration, headers, new INetworkResponseListener<Void>() {

            @Override
            public void onNetworkResponse(Void aVoid) {
                // Network communication completed correctly
                listener.onActivationRemoveSucceed();
            }

            @Override
            public void onNetworkError(Throwable t) {
                // Network error occurred
                listener.onActivationRemoveFailed(t);
            }
        });
    }

    /**
     * Compute the HTTP signature header for given GET request, URI identifier and query parameters using provided authentication information.
     * <p>
     * This method may block a main thread - make sure to dispatch it asynchronously.
     *
     * @param context        Context.
     * @param authentication An authentication instance specifying what factors should be used to sign the request.
     * @param uriId          URI identifier.
     * @param params         GET request query parameters
     * @return HTTP header with PowerAuth authorization signature when PA2Succeed returned in powerAuthErrorCode. In case of error return null header value.
     * @throws PowerAuthMissingConfigException thrown in case configuration is not present.
     */
    public PowerAuthAuthorizationHttpHeader requestGetSignatureWithAuthentication(@NonNull Context context, @NonNull PowerAuthAuthentication authentication, String uriId, Map<String, String> params) {
        byte[] body = this.mSession.prepareKeyValueDictionaryForDataSigning(params);
        return requestSignatureWithAuthentication(context, authentication, false, "GET", uriId, body);
    }

    /**
     * Compute the HTTP signature header for given HTTP method, URI identifier and HTTP request body using provided authentication information.
     * <p>
     * This method may block a main thread - make sure to dispatch it asynchronously.
     *
     * @param context        Context.
     * @param authentication An authentication instance specifying what factors should be used to sign the request.
     * @param method         HTTP method used for the signature computation.
     * @param uriId          URI identifier.
     * @param body           HTTP request body.
     * @return HTTP header with PowerAuth authorization signature when PA2Succeed returned in powerAuthErrorCode. In case of error return null header value.
     * @throws PowerAuthMissingConfigException thrown in case configuration is not present.
     */
    public PowerAuthAuthorizationHttpHeader requestSignatureWithAuthentication(@NonNull Context context, @NonNull PowerAuthAuthentication authentication, String method, String uriId, byte[] body) {
        return requestSignatureWithAuthentication(context, authentication, false, method, uriId, body);
    }

    /**
     * Compute the HTTP signature header with vault unlock flag for given HTTP method, URI identifier and HTTP request body using provided authentication information.
     * <p>
     * This method may block a main thread - make sure to dispatch it asynchronously.
     *
     * @param context        Context.
     * @param authentication An authentication instance specifying what factors should be used to sign the request.
     * @param vaultUnlock    A flag indicating this request is associate with vault unlock operation.
     * @param method         HTTP method used for the signature computation.
     * @param uriId          URI identifier.
     * @param body           HTTP request body.
     * @return HTTP header with PowerAuth authorization signature when PA2Succeed returned in powerAuthErrorCode. In case of error return null header value.
     * @throws PowerAuthMissingConfigException thrown in case configuration is not present.
     */
    public PowerAuthAuthorizationHttpHeader requestSignatureWithAuthentication(@NonNull Context context, @NonNull PowerAuthAuthentication authentication, boolean vaultUnlock, String method, String uriId, byte[] body) {
        // Check for the session setup
        if (mSession == null || !mSession.hasValidSetup()) {
            throwInvalidConfigurationException();
        }

        // Check if there is an activation present
        if (!mSession.hasValidActivation() && mSession.hasPendingActivation()) {
            return new PowerAuthAuthorizationHttpHeader(null, PowerAuthErrorCodes.PA2ErrorCodeMissingActivation);
        }

        // Generate signature key encryption keys
        SignatureUnlockKeys keys = signatureKeysForAuthentication(context, authentication);
        if (keys == null) {
            return new PowerAuthAuthorizationHttpHeader(null, PowerAuthErrorCodes.PA2ErrorCodeInvalidActivationData);
        }

        // Determine authentication factor type
        int signatureFactor = determineSignatureFactorForAuthentication(authentication, vaultUnlock);

        // Compute authorization header for provided values and return result.
        SignatureResult signatureResult = mSession.signHTTPRequest(body, method, uriId, keys, signatureFactor);

        // Update state after each successful calculation
        saveSerializedState();

        if (signatureResult.errorCode == ErrorCode.OK) {
            return new PowerAuthAuthorizationHttpHeader(signatureResult.signature, PowerAuthErrorCodes.PA2Succeed);
        } else {
            return new PowerAuthAuthorizationHttpHeader(null, PowerAuthErrorCodes.PA2ErrorCodeSignatureError);
        }
    }

    /**
     * Sign provided data with a private key that is stored in secure vault.
     * @param context Context.
     * @param authentication Authentication object for vault unlock request.
     * @param data Data to be signed.
     * @param listener Listener with callbacks to signature status.
     * @return Async task associated with vault unlock request.
     */
    public AsyncTask signDataWithDevicePrivateKey(@NonNull final Context context, @NonNull PowerAuthAuthentication authentication, @NonNull final byte[] data, @NonNull final IDataSignatureListener listener) {

        // Fetch vault encryption key using vault unlock request.
        return this.fetchEncryptedVaultUnlockKey(context, authentication, new IFetchEncryptedVaultUnlockKeyListener() {
            @Override
            public void onFetchEncryptedVaultUnlockKeySucceed(String encryptedEncryptionKey) {
                if (encryptedEncryptionKey != null) {
                    // Let's sign the data
                    SignatureUnlockKeys keys = new SignatureUnlockKeys(deviceRelatedKey(context), null, null);
                    byte[] signature = mSession.signDataWithDevicePrivateKey(encryptedEncryptionKey, keys, data);
                    // Propagate error
                    if (signature != null) {
                        listener.onDataSignedSucceed(signature);
                    } else {
                        listener.onDataSignedFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeInvalidActivationData));
                    }
                } else {
                    listener.onDataSignedFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeInvalidActivationState));
                }
            }

            @Override
            public void onFetchEncryptedVaultUnlockKeyFailed(Throwable t) {
                listener.onDataSignedFailed(t);
            }
        });
    }

    /**
     * Change the password using local re-encryption, do not validate old password by calling any endpoint.
     *
     * You are responsible for validating the old password against some server endpoint yourself before using it in this method.
     * If you do not validate the old password to make sure it is correct, calling this method will corrupt the local data, since
     * existing data will be decrypted using invalid PIN code and re-encrypted with a new one.
     *
     * @param oldPassword Old password, currently set to store the data.
     * @param newPassword New password to be set to store the data.
     * @return Returns 'true' in case password was changed without error, 'false' otherwise.
     * @throws PowerAuthMissingConfigException thrown in case configuration is not present.
     */
    public boolean changePasswordUnsafe(@NonNull final String oldPassword, @NonNull final String newPassword) {
        final int result = mSession.changeUserPassword(new Password(oldPassword), new Password(newPassword));
        if (result == ErrorCode.OK) {
            saveSerializedState();
            return true;
        }
        return false;
    }

    /**
     * Change the password, validate old password by calling a PowerAuth 2.0 Standard RESTful API endpoint '/pa/vault/unlock'.
     *
     * @param context     Context.
     * @param oldPassword Old password, currently set to store the data.
     * @param newPassword New password, to be set in case authentication with old password passes.
     * @param listener    The callback method with the password change result.
     * @return AsyncTask associated with the running request.
     * @throws PowerAuthMissingConfigException thrown in case configuration is not present.
     */
    public AsyncTask changePassword(@NonNull Context context, @NonNull final String oldPassword, @NonNull final String newPassword, @NonNull final IChangePasswordListener listener) {
        // Setup a new authentication object
        final PowerAuthAuthentication authentication = new PowerAuthAuthentication();
        authentication.usePossession = true;
        authentication.usePassword = oldPassword;

        return fetchEncryptedVaultUnlockKey(context, authentication, new IFetchEncryptedVaultUnlockKeyListener() {

            @Override
            public void onFetchEncryptedVaultUnlockKeySucceed(String encryptedEncryptionKey) {
                // Let's change the password
                final int result = mSession.changeUserPassword(new Password(oldPassword), new Password(newPassword));
                if (result == ErrorCode.OK) {
                    // Update state
                    saveSerializedState();

                    listener.onPasswordChangeSucceed();
                } else {
                    listener.onPasswordChangeFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeInvalidActivationState));
                }
            }

            @Override
            public void onFetchEncryptedVaultUnlockKeyFailed(Throwable t) {
                listener.onPasswordChangeFailed(t);
            }
        });
    }

    /**
     * Check if the current PowerAuth instance has biometry factor in place.
     *
     * @return True in case biometry factor is present, false otherwise.
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    public boolean hasBiometryFactor(@NonNull Context context) {
        // Check for the session setup
        if (mSession == null || !mSession.hasValidSetup()) {
            throwInvalidConfigurationException();
        }

        // Initialize keystore
        FingerprintKeystore keyStore = new FingerprintKeystore();
        if (!keyStore.isKeystoreReady()) {
            return false;
        }

        // Check if there is biometry factor in session, key in PA2Keychain and key in keystore.
        return mSession.hasBiometryFactor() && mBiometryKeychain.containsDataForKey(context, mKeychainConfiguration.getKeychainBiometryDefaultKey()) && keyStore.containsDefaultKey();
    }

    /**
     * Regenerate a biometry related factor key.
     * <p>
     * This method calls PowerAuth 2.0 Standard RESTful API endpoint '/pa/vault/unlock' to obtain the vault encryption key used for original private key decryption.
     *
     * @param context  Context.
     * @param password Password used for authentication during vault unlocking call.
     * @param listener The callback method with the encrypted key.
     * @return AsyncTask associated with the running request.
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    public @Nullable AsyncTask addBiometryFactor(@NonNull final Context context, final FragmentManager fragmentManager, final String title, final String description, String password, @NonNull final IAddBiometryFactorListener listener) {

        // Initial authentication object, used for vault unlock call on server
        final PowerAuthAuthentication authAuthentication = new PowerAuthAuthentication();
        authAuthentication.usePossession = true;
        authAuthentication.usePassword = password;

        // Fetch vault unlock key
        return fetchEncryptedVaultUnlockKey(context, authAuthentication, new IFetchEncryptedVaultUnlockKeyListener() {

            @Override
            public void onFetchEncryptedVaultUnlockKeySucceed(final String encryptedEncryptionKey) {
                if (encryptedEncryptionKey != null) {

                    // Authenticate using fingerprint to generate a key
                    authenticateUsingFingerprint(context, fragmentManager, title, description, true, new IFingerprintActionHandler() {
                        @Override
                        public void onFingerprintDialogCancelled() {
                            listener.onAddBiometryFactorFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeTouchIDCancel));
                        }

                        @Override
                        public void onFingerprintDialogSuccess(@Nullable byte[] biometricKeyEncrypted) {
                            // Let's add the biometry key
                            SignatureUnlockKeys keys = new SignatureUnlockKeys(deviceRelatedKey(context), biometricKeyEncrypted, null);
                            final int result = mSession.addBiometryFactor(encryptedEncryptionKey, keys);
                            if (result == ErrorCode.OK) {
                                // Update state after each successful calculations
                                saveSerializedState();

                                listener.onAddBiometryFactorSucceed();
                            } else {
                                listener.onAddBiometryFactorFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeInvalidActivationState));
                            }
                        }

                        @Override
                        public void onFingerprintInfoDialogClosed() {
                            listener.onAddBiometryFactorFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeTouchIDCancel));
                        }
                    });
                } else {
                    listener.onAddBiometryFactorFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeInvalidActivationData));
                }
            }

            @Override
            public void onFetchEncryptedVaultUnlockKeyFailed(Throwable t) {
                listener.onAddBiometryFactorFailed(t);
            }
        });
    }

    /**
     * Regenerate a biometry related factor key.
     * <p>
     * This method calls PowerAuth 2.0 Standard RESTful API endpoint '/pa/vault/unlock' to obtain the vault encryption key used for original private key decryption.
     *
     * @param context  Context.
     * @param password Password used for authentication during vault unlocking call.
     * @param encryptedBiometryKey Encrypted biometry key used for storing biometry related factor key.
     * @param listener The callback method with the encrypted key.
     * @return AsyncTask associated with the running request.
     */
    public @Nullable AsyncTask addBiometryFactor(@NonNull final Context context, String password, final byte[] encryptedBiometryKey, @NonNull final IAddBiometryFactorListener listener) {
        final PowerAuthAuthentication authAuthentication = new PowerAuthAuthentication();
        authAuthentication.usePossession = true;
        authAuthentication.usePassword = password;

        return fetchEncryptedVaultUnlockKey(context, authAuthentication, new IFetchEncryptedVaultUnlockKeyListener() {

            @Override
            public void onFetchEncryptedVaultUnlockKeySucceed(String encryptedEncryptionKey) {
                if (encryptedEncryptionKey != null) {
                    // Let's add the biometry key
                    SignatureUnlockKeys keys = new SignatureUnlockKeys(deviceRelatedKey(context), encryptedBiometryKey, null);
                    final int result = mSession.addBiometryFactor(encryptedEncryptionKey, keys);
                    if (result == ErrorCode.OK) {
                        // Update state after each successful calculations
                        saveSerializedState();
                        listener.onAddBiometryFactorSucceed();
                    } else {
                        listener.onAddBiometryFactorFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeInvalidActivationState));
                    }
                } else {
                    listener.onAddBiometryFactorFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeInvalidActivationState));
                }
            }

            @Override
            public void onFetchEncryptedVaultUnlockKeyFailed(Throwable t) {
                listener.onAddBiometryFactorFailed(t);
            }
        });
    }

    /**
     * Remove the biometry related factor key.
     *
     * @param context Context.
     * @return TRUE if the key was successfully removed, FALSE otherwise.
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    public boolean removeBiometryFactor(@NonNull Context context) {
        // Check for the session setup
        if (mSession == null || !mSession.hasValidSetup()) {
            throwInvalidConfigurationException();
        }

        final int result = mSession.removeBiometryFactor();
        if (result == ErrorCode.OK) {
            // Update state after each successful calculations
            saveSerializedState();
            mBiometryKeychain.removeDataForKey(context, mKeychainConfiguration.getKeychainBiometryDefaultKey());
            // Initialize keystore
            FingerprintKeystore keyStore = new FingerprintKeystore();
            if (keyStore.isKeystoreReady()) {
                keyStore.removeDefaultKey();
            }
        }
        return result == ErrorCode.OK;
    }

    /**
     * Generate an derived encryption key with given index.
     * <p>
     * This method calls PowerAuth 2.0 Standard RESTful API endpoint '/pa/vault/unlock' to obtain the vault encryption key used for subsequent key derivation using given index.
     *
     * @param context        Context.
     * @param authentication Authentication used for vault unlocking call.
     * @param index          Index of the derived key using KDF.
     * @param listener       The callback method with the derived encryption key.
     * @return AsyncTask associated with the running request.
     */
    public @Nullable AsyncTask fetchEncryptionKey(@NonNull final Context context, @NonNull PowerAuthAuthentication authentication, final long index, @NonNull final IFetchEncryptionKeyListener listener) {
        return fetchEncryptedVaultUnlockKey(context, authentication, new IFetchEncryptedVaultUnlockKeyListener() {

            @Override
            public void onFetchEncryptedVaultUnlockKeySucceed(String encryptedEncryptionKey) {

                // Let's unlock encryption key
                final SignatureUnlockKeys keys = new SignatureUnlockKeys(deviceRelatedKey(context), null, null);
                final byte[] key = mSession.deriveCryptographicKeyFromVaultKey(encryptedEncryptionKey, keys, index);
                if (key != null) {
                    listener.onFetchEncryptionKeySucceed(key);
                } else {
                    // Propagate error
                    listener.onFetchEncryptionKeyFailed(new PowerAuthErrorException(PowerAuthErrorCodes.PA2ErrorCodeInvalidActivationData));
                }

            }

            @Override
            public void onFetchEncryptedVaultUnlockKeyFailed(Throwable t) {
                listener.onFetchEncryptionKeyFailed(t);
            }
        });
    }

    /**
     * Validate a user password.
     * <p>
     * This method calls PowerAuth 2.0 Standard RESTful API endpoint '/pa/vault/unlock' to validate the signature value.
     * @param context  Context.
     * @param password Password to be verified.
     * @param listener The callback method with error associated with the password validation.
     * @return AsyncTask associated with the running request.
     */
    public @Nullable AsyncTask validatePasswordCorrect(@NonNull Context context, String password, @NonNull final IValidatePasswordListener listener) {
        PowerAuthAuthentication authentication = new PowerAuthAuthentication();
        authentication.usePossession = true;
        authentication.usePassword = password;
        return fetchEncryptedVaultUnlockKey(context, authentication, new IFetchEncryptedVaultUnlockKeyListener() {
            @Override
            public void onFetchEncryptedVaultUnlockKeySucceed(String encryptedEncryptionKey) {
                listener.onPasswordValid();
            }

            @Override
            public void onFetchEncryptedVaultUnlockKeyFailed(Throwable t) {
                listener.onPasswordValidationFailed(t);
            }
        });
    }

    /**
     * Authenticate a client using fingerprint authentication. In case of the authentication is successful and 'onFingerprintDialogSuccess' callback is called,
     * you can use 'biometricKeyEncrypted' as a 'useBiometry' key on 'PowerAuthAuthentication' instance.
     *
     * @param context Context.
     * @param fragmentManager Fragment manager for the dialog.
     * @param title Dialog title.
     * @param description Dialog description.
     * @param callback Callback with the authentication result.
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    public void authenticateUsingFingerprint(Context context, FragmentManager fragmentManager, String title, String description, final IFingerprintActionHandler callback) {
        authenticateUsingFingerprint(context, fragmentManager, title, description, false, callback);
    }

    /**
     * Authenticate a client using fingerprint authentication. In case of the authentication is successful and 'onFingerprintDialogSuccess' callback is called,
     * you can use 'biometricKeyEncrypted' as a 'useBiometry' key on 'PowerAuthAuthentication' instance.
     *
     * Use this method in case of activation of the fingerprint scanner - pass 'true' as 'forceGenerateNewKey'.
     *
     * @param context Context.
     * @param fragmentManager Fragment manager for the dialog.
     * @param title Dialog title.
     * @param description Dialog description.
     * @param forceGenerateNewKey Pass true to indicate that a new key should be generated in Keystore
     * @param callback Callback with the authentication result.
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    private void authenticateUsingFingerprint(final @NonNull Context context, final @NonNull FragmentManager fragmentManager, final @NonNull String title, final @NonNull String description, final boolean forceGenerateNewKey, final IFingerprintActionHandler callback) {

        final byte[] biometryKey;
        if (forceGenerateNewKey) { // new key has to be generated
            biometryKey = mSession.generateSignatureUnlockKey();
        } else { // old key should be used, if present
            biometryKey = mBiometryKeychain.dataForKey(context, mKeychainConfiguration.getKeychainBiometryDefaultKey());
        }

        // Build a new authentication dialog fragment instance.
        FingerprintAuthenticationDialogFragment dialog = new FingerprintAuthenticationDialogFragment.DialogFragmentBuilder()
                .title(title)
                .description(description)
                .biometricKey(biometryKey)
                .forceGenerateNewKey(forceGenerateNewKey)
                .build();

        // Set the provided fragment manager
        dialog.setFragmentManager(fragmentManager);

        // Augment the provided callback so that the key can be normalized
        // in 'onFingerprintDialogSuccess' before returning, without the need to
        // "break" the encryption provided by the dialog fragment itself.
        dialog.setAuthenticationCallback(new IFingerprintActionHandler() {
            @Override
            public void onFingerprintDialogCancelled() {
                callback.onFingerprintDialogCancelled();
            }

            @Override
            public void onFingerprintDialogSuccess(@Nullable byte[] biometricKeyEncrypted) {
                // Store the new key, if a new key was generated
                if (forceGenerateNewKey) {
                    mBiometryKeychain.putDataForKey(context, biometryKey, mKeychainConfiguration.getKeychainBiometryDefaultKey());
                }
                byte[] normalizedEncryptionKey = mSession.normalizeSignatureUnlockKeyFromData(biometricKeyEncrypted);
                callback.onFingerprintDialogSuccess(normalizedEncryptionKey);
            }

            @Override
            public void onFingerprintInfoDialogClosed() {
                callback.onFingerprintInfoDialogClosed();
            }
        });

        // Show the dialog
        dialog.show();
    }

}
