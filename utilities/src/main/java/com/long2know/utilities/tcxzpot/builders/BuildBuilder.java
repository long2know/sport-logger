package com.long2know.utilities.tcxzpot.builders;

import com.long2know.utilities.tcxzpot.Build;
import com.long2know.utilities.tcxzpot.BuildType;
import com.long2know.utilities.tcxzpot.Version;

public class BuildBuilder {

    public static BuildBuilder aBuild() {
        return new BuildBuilder();
    }

    private Version version;
    private BuildType type;
    private String time;

    private BuildBuilder() {}

    public BuildBuilder withVersion(Version version) {
        this.version = version;
        return this;
    }

    public BuildBuilder withVersion(VersionBuilder versionBuilder) {
        this.version = versionBuilder.build();
        return this;
    }

    public BuildBuilder withType(BuildType type) {
        this.type = type;
        return this;
    }

    public BuildBuilder withTime(String time) {
        this.time = time;
        return this;
    }

    public Build build() {
        validateArguments();
        return new Build(version, type, time);
    }

    private void validateArguments() {
        if(version == null) {
            throw new IllegalArgumentException("Build version must not be null");
        }
    }
}
