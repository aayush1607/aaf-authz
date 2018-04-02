/*******************************************************************************
 * ============LICENSE_START====================================================
 * * org.onap.aaf
 * * ===========================================================================
 * * Copyright © 2017 AT&T Intellectual Property. All rights reserved.
 * * ===========================================================================
 * * Licensed under the Apache License, Version 2.0 (the "License");
 * * you may not use this file except in compliance with the License.
 * * You may obtain a copy of the License at
 * * 
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 * * 
 *  * Unless required by applicable law or agreed to in writing, software
 * * distributed under the License is distributed on an "AS IS" BASIS,
 * * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * * See the License for the specific language governing permissions and
 * * limitations under the License.
 * * ============LICENSE_END====================================================
 * *
 * *
 ******************************************************************************/
package org.onap.aaf.cadi.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.onap.aaf.cadi.Access;
import org.onap.aaf.cadi.CachingLur;
import org.onap.aaf.cadi.CadiException;
import org.onap.aaf.cadi.CadiWrap;
import org.onap.aaf.cadi.Lur;
import org.onap.aaf.cadi.Permission;
import org.onap.aaf.cadi.PropAccess;
import org.onap.aaf.cadi.User;
import org.onap.aaf.cadi.CachedPrincipal.Resp;
import org.onap.aaf.cadi.filter.MapPermConverter;
import org.onap.aaf.cadi.lur.EpiLur;
import org.onap.aaf.cadi.principal.TaggedPrincipal;
import org.onap.aaf.cadi.taf.TafResp;

public class JU_CadiWrapTest {
	
	@Mock
	private HttpServletRequest request;
	
	@Mock
	private TafResp tafResp;
	
	@Mock
	private TaggedPrincipal principle;

	@Mock
	private Lur lur;

	@Before
	public void setUp() throws Exception {
		MockitoAnnotations.initMocks(this);
	}

	@Test
	public void testInstantiate() throws CadiException {
		Access a = new PropAccess();
		when(tafResp.getAccess()).thenReturn(a);
		
		lur.fishAll(isA(Principal.class), isA(List.class));
		
		EpiLur lur1 = new EpiLur(lur);
		
		CadiWrap wrap = new CadiWrap(request, tafResp, lur1);
		
		assertNull(wrap.getUserPrincipal());
		assertNull(wrap.getRemoteUser());
		assertNull(wrap.getUser());
		assertEquals(wrap.getPermissions(principle).size(), 0);
		assertTrue(wrap.access() instanceof PropAccess);
		
		byte[] arr = {'1','2'};
		wrap.setCred(arr);
		
		assertEquals(arr, wrap.getCred());
		
		wrap.setUser("User1");
		assertEquals("User1", wrap.getUser());
		
		wrap.invalidate("1");

		assertFalse(wrap.isUserInRole(null));
		
		wrap.set(tafResp, lur);
		
		wrap.invalidate("2");
		
		wrap.isUserInRole("User1");
	}

	@Test
	public void testInstantiateWithPermConverter() throws CadiException {
		Access a = new PropAccess();
		when(tafResp.getAccess()).thenReturn(a);
		when(tafResp.getPrincipal()).thenReturn(principle);
		
		// Anonymous object for testing purposes
		CachingLur<Permission> lur1 = new CachingLur<Permission>() {
			@Override public Permission createPerm(String p) { return null; }
			@Override public boolean fish(Principal bait, Permission pond) { return true; }
			@Override public void fishAll(Principal bait, List<Permission> permissions) { }
			@Override public void destroy() { }
			@Override public boolean handlesExclusively(Permission pond) { return false; }
			@Override public boolean handles(Principal principal) { return false; }
			@Override public void remove(String user) { }
			@Override public Resp reload(User<Permission> user) { return null; }
			@Override public void setDebug(String commaDelimIDsOrNull) { }
			@Override public void clear(Principal p, StringBuilder sb) { }
		};
		
		MapPermConverter pc = new MapPermConverter();
		
		CadiWrap wrap = new CadiWrap(request, tafResp, lur1, pc);
		
		assertNotNull(wrap.getUserPrincipal());
		assertNull(wrap.getRemoteUser());
		assertNull(wrap.getUser());
		
		byte[] arr = {'1','2'};
		wrap.setCred(arr);
		
		assertEquals(arr, wrap.getCred());
		
		wrap.setUser("User1");
		assertEquals("User1", wrap.getUser());
		
		wrap.invalidate("1");
		wrap.setPermConverter(new MapPermConverter());
		
		assertTrue(wrap.getLur() instanceof CachingLur);
		assertTrue(wrap.isUserInRole("User1"));
		
		wrap.set(tafResp, lur);
		assertFalse(wrap.isUserInRole("Perm1"));
	}
}