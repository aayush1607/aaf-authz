/**
 * ============LICENSE_START====================================================
 * org.onap.aaf
 * ===========================================================================
 * Copyright (c) 2019 IBM Intellectual Property. All rights reserved.
 * ===========================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ============LICENSE_END====================================================
 *
 */


package org.onap.aaf.auth.batch.reports.bodies;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.onap.aaf.auth.batch.reports.Notify;
import org.onap.aaf.auth.env.AuthzTrans;

public class JU_AbsCredBodyTest {

    @Test
    public void testUserWithValue() {
        List<String> row = new ArrayList<>();
        row.add("test");
        row.add("user");
        AbsCredBody absCredBody = new AbsCredBody("") {
            @Override
            public String body(AuthzTrans trans, Notify n, String id) {
                return null;
            }
        };
        Assert.assertEquals("user", absCredBody.user(row));
    }

    @Test
    public void testUserWithoutValue() {
        //String testStr = "test";
        List<String> row = Collections.emptyList();
        AbsCredBody absCredBody = new AbsCredBody("") {
            @Override
            public String body(AuthzTrans trans, Notify n, String id) {
                return null;
            }
        };
        Assert.assertNull(absCredBody.user(row));
    }
}