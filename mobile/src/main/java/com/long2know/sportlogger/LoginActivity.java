package com.long2know.sportlogger;

import android.annotation.TargetApi;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ParseException;
import android.net.Uri;
import android.net.http.SslError;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;

import androidx.appcompat.app.AppCompatActivity;

public class LoginActivity extends AppCompatActivity {

    private static final String CLIENT_ID = "33318";
    private static final String CLIENT_SECRET = "9a36918e04f27efc90bcf18a5656af18affbeef8";
    private static final String STATE = "somerandomstring";
    private static final String REDIRECT_URI = "https://localhost";
    private static final String AUTHORIZATION_URL = "https://www.strava.com/oauth/authorize";
    private static final String ACCESS_TOKEN_URL = "https://www.strava.com/oauth/token";
    private static final String LOGOUT_URL = "https://dev-sso.csoki.com/Account/Logout";
    private static final String RESPONSE_TYPE_PARAM = "response_type";
    private static final String RESPONSE_TYPE_VALUE = "code";
    private static final String GRANT_TYPE_PARAM = "grant_type";
    private static final String GRANT_TYPE = "authorization_code";
    private static final String SCOPE_PARAM = "scope";
    private static final String SCOPE_VALUE = "activity:write,read";
    private static final String CLIENT_ID_PARAM = "client_id";
    private static final String CLIENT_SECRET_PARAM = "client_secret";
    private static final String STATE_PARAM = "state";
    private static final String REDIRECT_URI_PARAM = "redirect_uri";
    private static final String QUESTION_MARK = "?";
    private static final String AMPERSAND = "&";
    private static final String EQUALS = "=";

    private WebView _webView;
    private ProgressBar _progressBar;
    private Bundle returnBundle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        disableSSLCertificateChecking();

        _webView = findViewById(R.id.loginWebView);
        _webView.requestFocus(View.FOCUS_DOWN);
        WebSettings webSettings = _webView.getSettings();
        webSettings.setJavaScriptEnabled(true);

        RelativeLayout layout = findViewById(R.id.loginLayout);
        _progressBar = new ProgressBar(this,null,android.R.attr.progressBarStyleLarge);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(100,100);
        params.addRule(RelativeLayout.CENTER_IN_PARENT);
        layout.addView(_progressBar,params);
        _progressBar.setVisibility(View.VISIBLE);

        // Create the WebView client
        _webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed(); // Ignore SSL certificate errors
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                //This method will be executed each time a page finished loading.
                //The only we do is dismiss the progressDialog, in case we are showing any.
                if (_progressBar != null && _progressBar.getVisibility() == View.VISIBLE) {
                    _progressBar.setVisibility(View.GONE);
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                final Uri uri = Uri.parse(url);
                return overrideUri(uri);
            }

            @TargetApi(Build.VERSION_CODES.N)
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                final Uri uri = request.getUrl();
                return overrideUri(uri);
            }

            private boolean overrideUri(final Uri uri) {
                //This method will be called when the Auth process redirect to our RedirectUri.
                //We will check the url looking for our RedirectUri.
                String authorizationUrl = uri.toString();
                if (authorizationUrl.startsWith(REDIRECT_URI)) {
                    Log.i("Authorize", "");
                    //We take from the url the authorizationToken and the state token. We have to check that the state token returned by the Service is the same we sent.
                    //If not, that means the request may be a result of CSRF and must be rejected.
                    String stateToken = uri.getQueryParameter(STATE_PARAM);
                    if (stateToken == null || !stateToken.equals(STATE)) {
                        Log.e("Authorize", "State token doesn't match");
                        return true;
                    }

                    //If the user doesn't allow authorization to our application, the authorizationToken Will be null.
                    String authorizationToken = uri.getQueryParameter(RESPONSE_TYPE_VALUE);
                    if (authorizationToken == null) {
                        Log.i("Authorize", "The user doesn't allow authorization.");
                        return true;
                    }
                    Log.i("Authorize", "Auth token received: " + authorizationToken);

                    //Generate URL for requesting Access Token
                    String accessTokenUrl = ACCESS_TOKEN_URL; // getAccessTokenUrl(authorizationToken);
                    //We make the request in a AsyncTask
                    PostRequestAsyncTask task = new PostRequestAsyncTask(authorizationToken);
                    task.execute(accessTokenUrl);
                } else {
                    //Default behaviour
                    Log.i("Authorize", "Redirecting to: " + authorizationUrl);
                    _webView.loadUrl(authorizationUrl);
                }
                return true;
            }
        });

//        // Force a logout
//        WebView webView = new WebView(this);
//        webView.setWebViewClient(new WebViewClient()
//        {
//            @Override
//            public void onPageFinished(WebView view, String url) {
//
//            }
//        });
//
//        webView.loadUrl(LOGOUT_URL);

        //Get the authorization Url
        String authUrl = getAuthorizationUrl();
        Log.i("Authorize", "Loading Auth Url: " + authUrl);
        //Load the authorization URL into the webView
        _webView.loadUrl(authUrl);
    }

    /**
     * Method that generates the url for get the access token from the Service
     *
     * @return Url
     */
//    private static String getAccessTokenUrl(String authorizationToken) {
//        String encodedUrl = REDIRECT_URI;
//        try {
//            encodedUrl = URLEncoder.encode(REDIRECT_URI, "UTF-8");
//        } catch (Exception e) { }
//        String url =
//            ACCESS_TOKEN_URL
//                + QUESTION_MARK
//                + GRANT_TYPE_PARAM + EQUALS + GRANT_TYPE
//                + AMPERSAND
//                + RESPONSE_TYPE_VALUE + EQUALS + authorizationToken
//                + AMPERSAND
//                + CLIENT_ID_PARAM + EQUALS + CLIENT_ID
//                + AMPERSAND
//                + CLIENT_SECRET_PARAM + EQUALS + CLIENT_SECRET
//                + AMPERSAND
//                + REDIRECT_URI_PARAM + EQUALS + encodedUrl;
//
//        Log.i("AccessToken", "Generated Url " + url);
//        return url;
//    }

    /**
     * Method that generates the url for get the authorization token from the Service
     *
     * @return Url
     */
    private static String getAuthorizationUrl() {
        String encodedUrl = REDIRECT_URI;
        try {
            encodedUrl = URLEncoder.encode(REDIRECT_URI, "UTF-8");
        } catch (Exception e) {
        }
        String url =
                AUTHORIZATION_URL
                        + QUESTION_MARK + RESPONSE_TYPE_PARAM + EQUALS + RESPONSE_TYPE_VALUE
                        + AMPERSAND + CLIENT_ID_PARAM + EQUALS + CLIENT_ID
                        + AMPERSAND + STATE_PARAM + EQUALS + STATE
                        + AMPERSAND + SCOPE_PARAM + EQUALS + SCOPE_VALUE
                        + AMPERSAND + REDIRECT_URI_PARAM + EQUALS + encodedUrl;

        Log.i("Authorize", "Generated Url " + url);
        return url;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        //getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    private class PostRequestAsyncTask extends AsyncTask<String, Void, Boolean> {
        private String _authorizationCode;

        public PostRequestAsyncTask(String authorizationCode) {
            _authorizationCode = authorizationCode;
        }

        @Override
        protected void onPreExecute() {
            _progressBar.setVisibility(View.VISIBLE);
        }

        @Override
        protected Boolean doInBackground(String[] urls) {
            HttpsURLConnection httpsConn = null;
            if (urls.length > 0) try {
                // Build up the body for the token request
                HashMap<String, String> postDataParams = new HashMap<String, String>() {
                    {
                        put(GRANT_TYPE_PARAM, GRANT_TYPE);
                        put(RESPONSE_TYPE_VALUE, _authorizationCode);
                        put(REDIRECT_URI_PARAM, REDIRECT_URI);
                        put(CLIENT_ID_PARAM, CLIENT_ID);
                        put(CLIENT_SECRET_PARAM, CLIENT_SECRET);
                    }
                };
                byte[] postData = getPostDataString(postDataParams).getBytes( StandardCharsets.UTF_8 );

                URL url = new URL(urls[0]);
                URLConnection urlConnection = url.openConnection();
                httpsConn = (HttpsURLConnection) urlConnection;
                httpsConn.setRequestMethod("POST");
                httpsConn.setUseCaches(false);
                httpsConn.setDoInput(true);
                httpsConn.setDoOutput(true);
                httpsConn.setRequestProperty("Content-Length", Integer.toString(postData.length));
                httpsConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                httpsConn.setRequestProperty( "charset", "utf-8");
                httpsConn.setConnectTimeout(5000); //set timeout to 5 seconds
                DataOutputStream writer = new DataOutputStream(httpsConn.getOutputStream());
                writer.write(postData);
                writer.flush();
                writer.close();

                InputStream stream = httpsConn.getInputStream();
                int responseCode = ((HttpsURLConnection) urlConnection).getResponseCode();

                // If status is OK 200
                if (responseCode == HttpsURLConnection.HTTP_OK) {
                    String result = "";
                    if (responseCode == HttpsURLConnection.HTTP_OK) {
                        String line;
                        BufferedReader br = new BufferedReader(new InputStreamReader(stream));
                        while ((line = br.readLine()) != null) {
                            result += line;
                        }
                    } else {
                        result = "";
                    }

                    //Convert the string result to a JSON Object
                    JSONObject resultJson = new JSONObject(result);
                    //Extract data from JSON Response
                    int expiresIn = resultJson.has("expires_in") ? resultJson.getInt("expires_in") : 0;
                    String accessToken = resultJson.has("access_token") ? resultJson.getString("access_token") : null;
                    String refreshToken = resultJson.has("refresh_token") ? resultJson.getString("refresh_token") : null;

                    Log.e("Token", "" + accessToken);
                    if (expiresIn > 0 && accessToken != null) {
                        Log.i("Authorize", "This is the access Token: " + accessToken + ". It will expires in " + expiresIn + " secs");

                        //Calculate date of expiration
                        Calendar calendar = Calendar.getInstance();
                        calendar.add(Calendar.SECOND, expiresIn);
                        long expireDate = calendar.getTimeInMillis();

                        // Store the access_token, refresh_token, and expiration.
                        SharedPreferences preferences = LoginActivity.this.getSharedPreferences("oauth_tokens", 0);
                        SharedPreferences.Editor editor = preferences.edit();
                        editor.putLong("expires_in", expireDate);
                        editor.putString("access_token", accessToken);
                        editor.putString("refresh_token", refreshToken);
                        editor.commit();
                        return true;
                    }
                }
            } catch (IOException e) {
                String result = "";
                String line;
                try {
                    InputStream stream = httpsConn.getErrorStream();
                    BufferedReader br = new BufferedReader(new InputStreamReader(stream));
                    while ((line = br.readLine()) != null) {
                        result += line;
                    }
                } catch (Exception ee) { }
                Log.e("Authorize", "Error Http response " + e.getLocalizedMessage());
                Log.e("Authorize", "Error " + result);
            } catch (ParseException e) {
                Log.e("Authorize", "Error Parsing Http response " + e.getLocalizedMessage());
            } catch (JSONException e) {
                Log.e("Authorize", "Error Parsing Http response " + e.getLocalizedMessage());
            }catch (Exception e) {
                Log.e("Authorize", "Error " + e.getLocalizedMessage());
            }

            return false;
        }

        private String getPostDataString(HashMap<String, String> params) throws UnsupportedEncodingException {
            StringBuilder result = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (first) first = false;
                else result.append("&");
                result.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
                result.append("=");
                result.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            }

            return result.toString();
        }

        @Override
        protected void onPostExecute(Boolean status) {
            if (_progressBar != null && _progressBar.getVisibility() == View.VISIBLE) {
                _progressBar.setVisibility(View.GONE);
            }
            if (status) {
                Intent result = new Intent();
                Bundle resultData = new Bundle();
                resultData.putString("result", "Hi!");
                result.putExtras(resultData);
                setResult(RESULT_OK, result);
                finish();
            } else {
                Intent result = new Intent();
                Bundle resultData = new Bundle();
                resultData.putString("result", "Hi!");
                result.putExtras(resultData);
                setResult(RESULT_CANCELED, result);
            }
        }
    }

    /**
     * Disables the SSL certificate checking for new instances of {@link HttpsURLConnection} This has been created to
     * aid testing on a local box, not for use on production.
     */
    private static void disableSSLCertificateChecking() {
        TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() {
                return null;
            }

            @Override
            public void checkClientTrusted(X509Certificate[] arg0, String arg1) throws CertificateException {
                // Not implemented
            }

            @Override
            public void checkServerTrusted(X509Certificate[] arg0, String arg1) throws CertificateException {
                // Not implemented
            }
        }};

        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
        } catch (KeyManagementException e) {
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }
}
