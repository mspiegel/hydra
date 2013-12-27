/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.addthis.hydra.task.output;

import com.addthis.bundle.core.Bundle;
import com.addthis.bundle.core.BundleFormat;
import com.addthis.bundle.core.BundleFormatted;
import com.addthis.bundle.core.kvp.KVBundle;
import com.addthis.bundle.core.kvp.KVBundleFormat;


public abstract class DataOutputTypeKV extends TaskDataOutput implements BundleFormatted {

    private final KVBundleFormat format = new KVBundleFormat();

    @Override
    public Bundle createBundle() {
        return new KVBundle(format);
    }

    @Override
    public BundleFormat getFormat() {
        return format;
    }
}