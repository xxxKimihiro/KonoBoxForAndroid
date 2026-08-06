package io.nekohasekai.sagernet.fmt.internal;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import io.nekohasekai.sagernet.fmt.KryoConverters;
import moe.matsuri.nb4a.utils.JavaUtil;

/**
 * Auto outbound group: ordered fallback or latency-based (urltest) selection over members.
 */
public class AutoGroupBean extends InternalBean {

    public static final int STRATEGY_ORDER = 0;
    public static final int STRATEGY_LATENCY = 1;

    public List<Long> proxies;
    public int strategy;

    @Override
    public String displayName() {
        if (JavaUtil.isNotBlank(name)) {
            return name;
        } else {
            return "AutoGroup " + Math.abs(hashCode());
        }
    }

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (name == null) name = "";
        if (proxies == null) {
            proxies = new ArrayList<>();
        }
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        output.writeInt(strategy);
        output.writeInt(proxies.size());
        for (Long proxy : proxies) {
            output.writeLong(proxy);
        }
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        strategy = input.readInt();
        int length = input.readInt();
        proxies = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            proxies.add(input.readLong());
        }
        if (version < 0) {
            // keep for future
        }
    }

    @NotNull
    @Override
    public AutoGroupBean clone() {
        return KryoConverters.deserialize(new AutoGroupBean(), KryoConverters.serialize(this));
    }

    public static final Creator<AutoGroupBean> CREATOR = new CREATOR<AutoGroupBean>() {
        @NonNull
        @Override
        public AutoGroupBean newInstance() {
            return new AutoGroupBean();
        }

        @Override
        public AutoGroupBean[] newArray(int size) {
            return new AutoGroupBean[size];
        }
    };
}
