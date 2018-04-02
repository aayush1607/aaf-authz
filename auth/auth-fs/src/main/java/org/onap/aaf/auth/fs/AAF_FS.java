/**
 * ============LICENSE_START====================================================
 * org.onap.aaf
 * ===========================================================================
 * Copyright (c) 2018 AT&T Intellectual Property. All rights reserved.
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

package org.onap.aaf.auth.fs;

import static org.onap.aaf.auth.rserv.HttpMethods.GET;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.onap.aaf.auth.env.AuthzEnv;
import org.onap.aaf.auth.env.AuthzTrans;
import org.onap.aaf.auth.env.AuthzTransOnlyFilter;
import org.onap.aaf.auth.rserv.CachingFileAccess;
import org.onap.aaf.auth.rserv.HttpCode;
import org.onap.aaf.auth.server.AbsService;
import org.onap.aaf.auth.server.JettyServiceStarter;
import org.onap.aaf.cadi.Access.Level;
import org.onap.aaf.cadi.CadiException;
import org.onap.aaf.cadi.LocatorException;
import org.onap.aaf.cadi.PropAccess;
import org.onap.aaf.cadi.config.Config;
import org.onap.aaf.cadi.register.Registrant;
import org.onap.aaf.cadi.register.RemoteRegistrant;
import org.onap.aaf.misc.env.APIException;


public class AAF_FS extends AbsService<AuthzEnv, AuthzTrans>  {

	public AAF_FS(final AuthzEnv env) throws APIException, IOException, CadiException {
		super(env.access(),env);
		try {
			///////////////////////  
			// File Server 
			///////////////////////
			// creates StaticSlot, needed for CachingFileAccess, and sets to public Dir
			env.staticSlot(CachingFileAccess.CFA_WEB_PATH,"aaf_public_dir");

			CachingFileAccess<AuthzTrans> cfa = new CachingFileAccess<AuthzTrans>(env);
			route(env,GET,"/:key", cfa); 
			route(env,GET,"/:key/:cmd", cfa);
			final String aaf_locate_url = access.getProperty(Config.AAF_LOCATE_URL, null);
			if(aaf_locate_url == null) {
				access.printf(Level.WARN, "Redirection requires property %s",Config.AAF_LOCATE_URL);
			} else {
				route(env,GET,"/", new Redirect(this,aaf_locate_url));
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	private static class Redirect extends HttpCode<AuthzTrans, AAF_FS> {
		private final String url;

		public Redirect(AAF_FS context,String url) {
			super(context, "Redirect to HTTP/S");
			this.url = url;
		}

		@Override
		public void handle(AuthzTrans trans, HttpServletRequest req, HttpServletResponse resp) throws Exception {
			trans.info().printf("Redirecting %s to HTTP/S %s", req.getRemoteAddr(), req.getLocalAddr());
			resp.sendRedirect(url);
		}
	};
	
	@Override
	public Filter[] filters() throws CadiException, LocatorException {
		return new Filter[] {
			new AuthzTransOnlyFilter(env)
		};
	}

	@SuppressWarnings("unchecked")
	@Override
	public Registrant<AuthzEnv>[] registrants(final int port) throws CadiException, LocatorException {
		return new Registrant[] {
			new RemoteRegistrant<AuthzEnv>(aafCon(),app_name,app_version,port)
		};
	}
	
	public static void main(final String[] args) {
		PropAccess propAccess = new PropAccess(args);
		try {
 			AAF_FS service = new AAF_FS(new AuthzEnv(propAccess));
//			env.setLog4JNames("log4j.properties","authz","fs","audit","init",null);
			JettyServiceStarter<AuthzEnv,AuthzTrans> jss = new JettyServiceStarter<AuthzEnv,AuthzTrans>(service);
			jss.insecure().start();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}