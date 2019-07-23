package com.long2know.utilities.models;

public class OAuthServer {
    public String CLIENT_ID = "33318";
    public String CLIENT_SECRET = "9a36918e04f27efc90bcf18a5656af18affbeef8";
    public String STATE = "somerandomstring";
    public String REDIRECT_URI = "https://localhost";
    public String AUTHORIZATION_URL = "https://www.strava.com/oauth/authorize";
    public String ACCESS_TOKEN_URL = "https://www.strava.com/oauth/token";
    public String LOGOUT_URL = "https://dev-sso.csoki.com/Account/Logout";
    public String RESPONSE_TYPE_PARAM = "response_type";
    public String RESPONSE_TYPE_VALUE = "code";
    public String GRANT_TYPE_PARAM = "grant_type";
    public String GRANT_TYPE = "authorization_code";
    public String SCOPE_PARAM = "scope";
    public String SCOPE_VALUE = "activity:write,read";
}
