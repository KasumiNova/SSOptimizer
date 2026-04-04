package com.thoughtworks.xstream.core.util;

import github.kasuminova.ssoptimizer.common.save.XStreamObjectIdDictionaryHelper;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XStreamObjectIdDictionaryHelperForeignWrapperTest {
    @Test
    void supportsPackagePrivateWrapperFromForeignPackage() {
        Object key = new Object();
        Map<Object, Object> map = new HashMap<>();
        map.put(new ForeignPackageWrapper(key), "ref-foreign");

        assertEquals("ref-foreign", XStreamObjectIdDictionaryHelper.lookupId(map, key));
        assertTrue(XStreamObjectIdDictionaryHelper.containsId(map, key));
    }

    @Test
    void supportsPackagePrivateWrapperWithInheritedGetMethod() {
        Object key = new Object();
        Map<Object, Object> map = new HashMap<>();
        map.put(new ForeignInheritedWrapper(key), "ref-inherited");

        assertEquals("ref-inherited", XStreamObjectIdDictionaryHelper.lookupId(map, key));
        assertTrue(XStreamObjectIdDictionaryHelper.containsId(map, key));
    }

    static final class ForeignPackageWrapper {
        private final Object target;
        private final int    hashCode;

        ForeignPackageWrapper(final Object target) {
            this.target = target;
            this.hashCode = System.identityHashCode(target);
        }

        public Object get() {
            return target;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(final Object other) {
            if (other instanceof ForeignPackageWrapper wrapper) {
                return wrapper.target == target;
            }
            return false;
        }
    }

    static final class ForeignInheritedWrapper extends WeakReference<Object> {
        private final int hashCode;

        ForeignInheritedWrapper(final Object target) {
            super(target);
            this.hashCode = System.identityHashCode(target);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(final Object other) {
            if (other instanceof ForeignInheritedWrapper wrapper) {
                return wrapper.get() == get();
            }
            return false;
        }
    }
}