package com.long2know.utilities.tcxzpot.builders;

import com.long2know.utilities.tcxzpot.AbstractSource;
import com.long2know.utilities.tcxzpot.Activities;
import com.long2know.utilities.tcxzpot.TCXExtension;
import com.long2know.utilities.tcxzpot.TrainingCenterDatabase;

public class TrainingCenterDatabaseBuilder {

    private TCXExtension[] extensions;

    public static TrainingCenterDatabaseBuilder trainingCenterDatabase() {
        return new TrainingCenterDatabaseBuilder();
    }

    private TrainingCenterDatabaseBuilder() {}

    private Activities activities;
    private AbstractSource author;

    public TrainingCenterDatabaseBuilder withActivities(Activities activities) {
        this.activities = activities;
        return this;
    }

    public TrainingCenterDatabaseBuilder withAuthor(AbstractSource author) {
        this.author = author;
        return this;
    }

    public TrainingCenterDatabaseBuilder withAuthor(AbstractSourceBuilder authorBuilder) {
        this.author = authorBuilder.build();
        return this;
    }

    public TrainingCenterDatabaseBuilder withExtensions(TCXExtension... extensions) {
        this.extensions = extensions;
        return this;
    }

    public TrainingCenterDatabase build() {
        return new TrainingCenterDatabase(activities, author, extensions);
    }
}
