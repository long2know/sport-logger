package com.long2know.sportlogger.acquisition;

public enum RouteStatus {
    UNKNOWN(false),
    AVAILABLE(true),
    ACQUIRING(false),
    LOCATION_DISABLED(false),
    PERMISSION_DENIED(false),
    APPROXIMATE_ONLY(false),
    NO_GNSS(false),
    NO_COMPATIBLE_PROVIDER(false),
    UNAVAILABLE(false);

    private final boolean routeAvailable;

    RouteStatus(boolean routeAvailable) {
        this.routeAvailable = routeAvailable;
    }

    public boolean isRouteAvailable() {
        return routeAvailable;
    }

    public boolean shouldShowBanner() {
        return this != UNKNOWN && this != AVAILABLE;
    }
}
