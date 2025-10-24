// === Api Interfaces ===
package ir.cafebazaar.bazaarpay.data.bazaar.account.api

import ir.cafebazaar.bazaarpay.data.bazaar.account.models.getotptoken.request.GetOtpTokenSingleRequest
import ir.cafebazaar.bazaarpay.data.bazaar.account.models.getotptoken.response.GetOtpTokenResponseDto
import ir.cafebazaar.bazaarpay.data.bazaar.account.models.getotptokenbycall.request.GetOtpTokenByCallSingleRequest
import ir.cafebazaar.bazaarpay.data.bazaar.account.models.getotptokenbycall.response.GetOtpTokenByCallResponseDto
import ir.cafebazaar.bazaarpay.data.bazaar.account.models.refreshaccesstoken.request.GetAccessTokenSingleRequest
import ir.cafebazaar.bazaarpay.data.bazaar.account.models.refreshaccesstoken.response.GetAccessTokenResponseDto
import ir.cafebazaar.bazaarpay.data.bazaar.account.models.verifyotptoken.request.VerifyOtpTokenSingleRequest
import ir.cafebazaar.bazaarpay.data.bazaar.account.models.verifyotptoken.response.VerifyOtpTokenResponseDto
import retrofit2.http.Body
import retrofit2.http.POST

internal interface AccountService {

    @POST("auth/v1/get-otp-token/")
    suspend fun getOtpToken(@Body request: GetOtpTokenSingleRequest): GetOtpTokenResponseDto

    @POST("auth/v1/get-otp-token-by-call/")
    suspend fun getOtpTokenByCall(@Body request: GetOtpTokenByCallSingleRequest): GetOtpTokenByCallResponseDto

    @POST("auth/v1/verify-otp-token/")
    suspend fun verifyOtpToken(@Body request: VerifyOtpTokenSingleRequest): VerifyOtpTokenResponseDto

    @POST("auth/v1/get-access-token/")
    suspend fun getAccessToken(@Body request: GetAccessTokenSingleRequest): GetAccessTokenResponseDto
}

package ir.cafebazaar.bazaarpay.data.bazaar.account.api

import ir.cafebazaar.bazaarpay.data.bazaar.account.models.userinfo.AutoLoginUserInfoReplyDto
import retrofit2.http.GET
import retrofit2.http.Query

internal interface UserInfoService {

    @GET("badje/v1/user/info/")
    suspend fun getUserInfo(@Query(CHECKOUT_TOKEN_LABEL) checkoutToken: String?): AutoLoginUserInfoReplyDto

    companion object {
        const val CHECKOUT_TOKEN_LABEL = "checkout_token"
    }
}

// === Models ===
package ir.cafebazaar.bazaarpay.data.bazaar.account.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

// Get OTP Request
data class GetOtpTokenSingleRequest(@SerializedName("phone") val phone: String)

// Get OTP Response
data class GetOtpTokenResponseDto(
    @SerializedName("waiting_seconds") val waitingSeconds: Long,
    @SerializedName("call_is_enabled") val callIsEnabled: Boolean
) {
    fun toWaitingTime(): WaitingTimeWithEnableCall = WaitingTimeWithEnableCall(waitingSeconds, callIsEnabled)
}

@Parcelize
data class WaitingTimeWithEnableCall(val seconds: Long, val isCallEnabled: Boolean) : Parcelable

// Get OTP By Call Request
data class GetOtpTokenByCallSingleRequest(@SerializedName("phone") val phone: String)

// Get OTP By Call Response
data class GetOtpTokenByCallResponseDto(
    @SerializedName("waiting_seconds") val waitingSeconds: Long
) {
    fun toWaitingTime(): WaitingTime = WaitingTime(waitingSeconds)
}

@Parcelize
data class WaitingTime(val seconds: Long) : Parcelable

// Verify OTP Request
data class VerifyOtpTokenSingleRequest(
    @SerializedName("phone") val phone: String,
    @SerializedName("token") val token: String
)

// Verify OTP Response
data class VerifyOtpTokenResponseDto(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String
) {
    fun toLoginResponse(): LoginResponse = LoginResponse(refreshToken, accessToken)
}

data class LoginResponse(val refreshToken: String, val accessToken: String)

// Refresh Access Token Request
data class GetAccessTokenSingleRequest(@SerializedName("refresh_token") val refreshToken: String)

// Refresh Access Token Response
data class GetAccessTokenResponseDto(@SerializedName("access_token") val accessToken: String)

// User Info Response
data class AutoLoginUserInfoReplyDto(@SerializedName("phone_number") val phoneNumber: String) {
    fun toUserInfo(): UserInfo = UserInfo(phoneNumber)
}

data class UserInfo(val phoneNumber: String)

// === Data Sources ===
package ir.cafebazaar.bazaarpay.data.bazaar.account.datasource

import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.data.SharedDataSource
import ir.cafebazaar.bazaarpay.data.bazaar.account.models.WaitingTime
import ir.cafebazaar.bazaarpay.data.bazaar.account.models.WaitingTimeWithEnableCall
import ir.cafebazaar.bazaarpay.data.bazaar.account.models.LoginResponse
import ir.cafebazaar.bazaarpay.data.bazaar.account.models.UserInfo
import ir.cafebazaar.bazaarpay.extensions.safeApiCall
import ir.cafebazaar.bazaarpay.models.GlobalDispatchers
import ir.cafebazaar.bazaarpay.utils.Either
import kotlinx.coroutines.withContext

internal class AccountLocalDataSource(private val shared: SharedDataSource = ServiceLocator.get(ServiceLocator.ACCOUNT)) {

    var accessToken: String by shared.delegate(ACCESS_TOKEN, "")
    var refreshToken: String by shared.delegate(REFRESH_TOKEN, "")
    var accessTokenTimestamp: Long by shared.delegate(ACCESS_TOKEN_TIMESTAMP, 0L)
    private var autoFillPhonesStr: String by shared.delegate(AUTO_FILL_PHONES, "")
    var loginPhone: String by shared.delegate(LOGIN_PHONE, "")

    fun getAutoFillPhones(): List<String> = autoFillPhonesStr.split(SEPARATOR).filter { it.isNotBlank() }

    fun addAutoFillPhone(phone: String) {
        if (phone.isBlank()) return
        loginPhone = phone
        val phones = getAutoFillPhones().toMutableSet().apply { add(phone) }.joinToString(SEPARATOR)
        autoFillPhonesStr = phones
    }

    fun clearTokens() {
        accessToken = ""
        refreshToken = ""
        accessTokenTimestamp = 0L
        loginPhone = ""
        shared.commit()
    }

    companion object {
        private const val ACCESS_TOKEN = "access_token"
        private const val REFRESH_TOKEN = "refresh_token"
        private const val ACCESS_TOKEN_TIMESTAMP = "access_token_timestamp"
        private const val AUTO_FILL_PHONES = "auto_fill_phones"
        private const val LOGIN_PHONE = "login_phone"
        private const val SEPARATOR = ","
    }
}

internal class AccountRemoteDataSource {

    private val accountService: AccountService by lazy { ServiceLocator.get() }
    private val userInfoService: UserInfoService? by lazy { ServiceLocator.getOrNull() }
    private val dispatchers: GlobalDispatchers by lazy { ServiceLocator.get() }
    private val checkoutToken: String? by lazy { ServiceLocator.getOrNull(ServiceLocator.CHECKOUT_TOKEN) }

    suspend fun getOtpToken(phone: String): Either<WaitingTimeWithEnableCall> = withContext(dispatchers.io) {
        safeApiCall { accountService.getOtpToken(GetOtpTokenSingleRequest(phone)) }.map { it.toWaitingTime() }
    }

    suspend fun getOtpTokenByCall(phone: String): Either<WaitingTime> = withContext(dispatchers.io) {
        safeApiCall { accountService.getOtpTokenByCall(GetOtpTokenByCallSingleRequest(phone)) }.map { it.toWaitingTime() }
    }

    suspend fun verifyOtpToken(phone: String, code: String): Either<LoginResponse> = withContext(dispatchers.io) {
        safeApiCall { accountService.verifyOtpToken(VerifyOtpTokenSingleRequest(phone, code)) }.map { it.toLoginResponse() }
    }

    suspend fun refreshAccessToken(refreshToken: String): Either<String> = withContext(dispatchers.io) {
        safeApiCall { accountService.getAccessToken(GetAccessTokenSingleRequest(refreshToken)) }.map { it.accessToken }
    }

    suspend fun getUserInfo(): Either<UserInfo> = withContext(dispatchers.io) {
        safeApiCall { userInfoService?.getUserInfo(checkoutToken) ?: throw IllegalStateException("No service") }.map { it.toUserInfo() }
    }
}

package ir.cafebazaar.bazaarpay.data.bazaar.account.datasource

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ir.cafebazaar.bazaarpay.data.SharedDataSource

internal class AccountSharedDataSource(context: Context) : SharedDataSource() {

    override val preferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        ACCOUNT_FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val ACCOUNT_FILE_NAME = "account_encrypted_prefs"
    }
}

// === Repository ===
package ir.cafebazaar.bazaarpay.data.bazaar.account

import android.content.Intent
import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.data.bazaar.account.datasource.AccountLocalDataSource
import ir.cafebazaar.bazaarpay.data.bazaar.account.datasource.AccountRemoteDataSource
import ir.cafebazaar.bazaarpay.data.bazaar.models.ErrorModel
import ir.cafebazaar.bazaarpay.extensions.isValidPhone
import ir.cafebazaar.bazaarpay.models.GlobalDispatchers
import ir.cafebazaar.bazaarpay.utils.Either
import ir.cafebazaar.bazaarpay.utils.doOnFailure
import ir.cafebazaar.bazaarpay.utils.doOnSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal class AccountRepository {

    private val localDataSource: AccountLocalDataSource = AccountLocalDataSource()
    private val remoteDataSource: AccountRemoteDataSource = AccountRemoteDataSource()
    private val dispatchers: GlobalDispatchers = ServiceLocator.get()

    private val _smsPermissionFlow: MutableSharedFlow<Intent> = MutableSharedFlow(replay = 1)
    val smsPermissionFlow: Flow<Intent> = _smsPermissionFlow.asSharedFlow()

    private val _loginStateFlow: MutableSharedFlow<Boolean> = MutableSharedFlow(replay = 1)
    val loginStateFlow: Flow<Boolean> = _loginStateFlow.asSharedFlow()

    init {
        _loginStateFlow.tryEmit(isLoggedIn())
    }

    fun isLoggedIn(): Boolean = localDataSource.accessToken.isNotBlank()

    fun needsLogin(): Boolean {
        return !isLoggedIn() && !isAutoLoginEnabled()
    }

    private fun isAutoLoginEnabled(): Boolean {
        val oldEnabled = ServiceLocator.getOrNull<Boolean>(ServiceLocator.IS_AUTO_LOGIN_ENABLE) ?: false
        val newEnabled = ServiceLocator.getOrNull<String>(ServiceLocator.AUTO_LOGIN_TOKEN).isNullOrBlank().not()
        return oldEnabled || newEnabled
    }

    suspend fun getOtpToken(phone: String): Either<WaitingTimeWithEnableCall> {
        if (!phone.isValidPhone()) return Either.Failure(ErrorModel.InputNotValid("Invalid phone"))
        return remoteDataSource.getOtpToken(phone)
    }

    suspend fun getOtpTokenByCall(phone: String): Either<WaitingTime> {
        if (!phone.isValidPhone()) return Either.Failure(ErrorModel.InputNotValid("Invalid phone"))
        return remoteDataSource.getOtpTokenByCall(phone)
    }

    suspend fun verifyOtpToken(phone: String, code: String): Either<LoginResponse> = withContext(dispatchers.io) {
        remoteDataSource.verifyOtpToken(phone, code).doOnSuccess { response ->
            localDataSource.accessToken = response.accessToken
            localDataSource.refreshToken = response.refreshToken
            localDataSource.accessTokenTimestamp = System.currentTimeMillis()
            localDataSource.addAutoFillPhone(phone)
            _loginStateFlow.emit(true)
        }.doOnFailure { _loginStateFlow.emit(false) }
    }

    suspend fun refreshAccessTokenIfNeeded(): Either<String> = withContext(dispatchers.io) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - localDataSource.accessTokenTimestamp < ACCESS_TOKEN_EXPIRY_MILLIS) {
            return@withContext Either.Success(localDataSource.accessToken)
        }
        remoteDataSource.refreshAccessToken(localDataSource.refreshToken).doOnSuccess { token ->
            localDataSource.accessToken = token
            localDataSource.accessTokenTimestamp = currentTime
        }
    }

    suspend fun getUserPhone(): String = withContext(dispatchers.io) {
        localDataSource.loginPhone.ifBlank {
            remoteDataSource.getUserInfo().getOrNull()?.phoneNumber.orEmpty().also { phone ->
                if (phone.isNotBlank()) localDataSource.addAutoFillPhone(phone)
            }
        }
    }

    suspend fun getAutoFillPhones(): List<String> = withContext(dispatchers.io) {
        localDataSource.getAutoFillPhones()
    }

    fun logout() {
        localDataSource.clearTokens()
        _loginStateFlow.tryEmit(false)
    }

    suspend fun setSmsPermissionIntent(intent: Intent) {
        _smsPermissionFlow.emit(intent)
    }

    // New Feature: Retry on failure for OTP
    suspend fun getOtpTokenWithRetry(phone: String, maxRetries: Int = 3): Either<WaitingTimeWithEnableCall> {
        var retries = 0
        while (retries < maxRetries) {
            val result = getOtpToken(phone)
            if (result.isSuccess()) return result
            if ((result.errorOrNull() as? ErrorModel.NetworkConnection) == null) return result
            retries++
            withContext(dispatchers.io) { kotlinx.coroutines.delay(1000L * retries) }
        }
        return Either.Failure(ErrorModel.NetworkConnection("Max retries reached"))
    }

    companion object {
        private val ACCESS_TOKEN_EXPIRY_MILLIS = TimeUnit.MINUTES.toMillis(5) // 5 minutes for safety
    }
}

