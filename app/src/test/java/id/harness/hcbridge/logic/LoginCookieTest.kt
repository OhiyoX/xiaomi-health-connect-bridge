package id.harness.hcbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** WebView 登录回传：从 CookieManager 的 cookie 串里取 userId / passToken。 */
class LoginCookieTest {

    @Test
    fun parsesUserIdAndPassToken() {
        val c = "cUserId=abc; userId=2574290350; passToken=V1:abc/def+ghi==; serviceToken=zzz"
        assertEquals("2574290350" to "V1:abc/def+ghi==", LoginCookie.parse(c))
    }

    @Test
    fun ignoresSameSuffixCookieNames() {
        assertNull("只有 cUserId/otherUserId，不能当 userId", LoginCookie.parse("cUserId=1; otherUserId=2; passToken=x"))
    }

    @Test
    fun returnsNullWhenPassTokenMissing() {
        assertNull(LoginCookie.parse("userId=1; serviceToken=x"))
    }

    @Test
    fun handlesSpacesQuotesAndEmptyValues() {
        val c = " userId = \"2574\" ; passToken = V1:y ; userId="
        assertEquals("2574" to "V1:y", LoginCookie.parse(c))
    }

    @Test
    fun returnsNullForBlankOrMalformedInput() {
        assertNull(LoginCookie.parse(null))
        assertNull(LoginCookie.parse(""))
        assertNull(LoginCookie.parse("novalue; =x; passToken="))
    }
}
