package com.long2know.sportlogger.acquisition.healthservices;

interface HealthServicesSessionJournal {
    int activeActivityId();

    void markActive(int activityId);

    void clear();
}
