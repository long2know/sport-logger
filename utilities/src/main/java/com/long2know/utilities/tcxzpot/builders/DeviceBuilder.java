package com.long2know.utilities.tcxzpot.builders;

import com.long2know.utilities.tcxzpot.Device;
import com.long2know.utilities.tcxzpot.Version;

import static com.long2know.utilities.tcxzpot.builders.StringUtil.isNullOrEmpty;

public class DeviceBuilder extends AbstractSourceBuilder {

    public static DeviceBuilder device(String name) {
        return new DeviceBuilder(name);
    }

    private String name;
    private Integer unitId;
    private Integer productId;
    private Version version;

    private DeviceBuilder(String name) {
        this.name = name;
    }

    public DeviceBuilder withUnitId(int unitId) {
        this.unitId = unitId;
        return this;
    }

    public DeviceBuilder withProductId(int productId) {
        this.productId = productId;
        return this;
    }

    public DeviceBuilder withVersion(Version version) {
        this.version = version;
        return this;
    }

    public DeviceBuilder withVersion(VersionBuilder versionBuilder) {
        this.version = versionBuilder.build();
        return this;
    }

    public Device build() {
        if(isNullOrEmpty(name)) {
            throw new IllegalArgumentException("Device must have a non-empty Name");
        }
        if(unitId == null) {
            throw new IllegalArgumentException("Device must have a UnitId");
        }
        if(productId == null) {
            throw new IllegalArgumentException("Device must have a ProductId");
        }
        if(version == null) {
            throw new IllegalArgumentException("Device must have a Version");
        }
        return new Device(name, unitId, productId, version);
    }
}
