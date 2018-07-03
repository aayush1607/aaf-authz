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

package org.onap.aaf.cadi.taf.cert;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;

import javax.net.ssl.TrustManagerFactory;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.onap.aaf.cadi.Access;
import org.onap.aaf.cadi.Access.Level;
import org.onap.aaf.cadi.CachedPrincipal;
import org.onap.aaf.cadi.CachedPrincipal.Resp;
import org.onap.aaf.cadi.CadiException;
import org.onap.aaf.cadi.CredVal;
import org.onap.aaf.cadi.Lur;
import org.onap.aaf.cadi.Symm;
import org.onap.aaf.cadi.Taf.LifeForm;
import org.onap.aaf.cadi.config.Config;
import org.onap.aaf.cadi.config.SecurityInfo;
import org.onap.aaf.cadi.config.SecurityInfoC;
import org.onap.aaf.cadi.principal.TaggedPrincipal;
import org.onap.aaf.cadi.principal.X509Principal;
import org.onap.aaf.cadi.taf.HttpTaf;
import org.onap.aaf.cadi.taf.TafResp;
import org.onap.aaf.cadi.taf.TafResp.RESP;
import org.onap.aaf.cadi.taf.basic.BasicHttpTaf;
import org.onap.aaf.cadi.util.Split;

public class X509Taf implements HttpTaf {
	
	private static final String CERTIFICATE_NOT_VALID_FOR_AUTHENTICATION = "Certificate NOT valid for Authentication";
	public static final CertificateFactory certFactory;
	public static final MessageDigest messageDigest;
	public static final TrustManagerFactory tmf;
	private Access access;
	private CertIdentity[] certIdents;
//	private Lur lur;
	private ArrayList<String> cadiIssuers;
	private String env;
	private SecurityInfo si;
	private BasicHttpTaf bht;

	static {
		try {
			certFactory = CertificateFactory.getInstance("X.509");
			messageDigest = MessageDigest.getInstance("SHA-256"); // use this to clone
			tmf = TrustManagerFactory.getInstance(SecurityInfoC.SSL_KEY_MANAGER_FACTORY_ALGORITHM);
		} catch (Exception e) {
			throw new RuntimeException("X.509 and SHA-256 are required for X509Taf",e);
		}
	}
	
	public X509Taf(Access access, Lur lur, CertIdentity ... cis) throws CertificateException, NoSuchAlgorithmException, CadiException {
		this.access = access;
		env = access.getProperty(Config.AAF_ENV,null);
		if(env==null) {
			throw new CadiException("X509Taf requires Environment ("+Config.AAF_ENV+") to be set.");
		}
//		this.lur = lur;
		this.cadiIssuers = new ArrayList<String>();
		for(String ci : access.getProperty(Config.CADI_X509_ISSUERS, "").split(":")) {
			access.printf(Level.INIT, "Trusting Identity for Certificates signed by \"%s\"",ci);
			cadiIssuers.add(ci);
		}
		try {
			Class<?> dci = access.classLoader().loadClass("org.onap.aaf.auth.direct.DirectCertIdentity");
			if(dci==null) {
				certIdents = cis;
			} else {
				CertIdentity temp[] = new CertIdentity[cis.length+1];
				System.arraycopy(cis, 0, temp, 1, cis.length);
				temp[0] = (CertIdentity) dci.newInstance();
				certIdents=temp;
			}
		} catch (Exception e) {
			certIdents = cis;
		}
		
		si = new SecurityInfo(access);
	}

	public static final X509Certificate getCert(byte[] certBytes) throws CertificateException {
		ByteArrayInputStream bais = new ByteArrayInputStream(certBytes);
		return (X509Certificate)certFactory.generateCertificate(bais);
	}

	public static final byte[] getFingerPrint(byte[] ba) {
		MessageDigest md;
		try {
			md = (MessageDigest)messageDigest.clone();
		} catch (CloneNotSupportedException e) {
			// should never get here
			return new byte[0];
		}
		md.update(ba);
		return md.digest();
	}

	@Override
	public TafResp validate(LifeForm reading, HttpServletRequest req, HttpServletResponse resp) {
		// Check for Mutual SSL
		try {
			X509Certificate[] certarr = (X509Certificate[])req.getAttribute("javax.servlet.request.X509Certificate");
			if(certarr!=null && certarr.length>0) {
				si.checkClientTrusted(certarr);
				// Note: If the Issuer is not in the TrustStore, it's not added to the Cert list
				String issuer = certarr[0].getIssuerDN().toString();
				if(cadiIssuers.contains(issuer)) {
					String subject = certarr[0].getSubjectDN().getName();
					// avoiding extra object creation, since this is validated EVERY transaction with a Cert
					int at = subject.indexOf('@');
					if(at>=0) {
						int start = subject.lastIndexOf(',', at);
						if(start<0) {
							start = 0;
						}
						int end = subject.indexOf(',', at);
						if(end<0) {
							end=subject.length();
						}
						int temp;
						if(((temp=subject.indexOf("OU=",start))>=0 && temp<end) || 
						   ((temp=subject.indexOf("CN=",start))>=0 && temp<end)) {
							String[] sa = Split.splitTrim(':', subject, temp+3,end);
							if(sa.length==1 || (sa.length>1 && env!=null && env.equals(sa[1]))) { // Check Environment 
								return new X509HttpTafResp(access, 
										new X509Principal(sa[0], certarr[0],(byte[])null,bht), 
										"X509Taf validated " + sa[0] + (sa.length<2?"":" for aaf_env " + env ), RESP.IS_AUTHENTICATED);
							}
						}
						
					}
				}
			}
		

			byte[] array = null;
			byte[] certBytes = null;
			X509Certificate cert=null;
			String responseText=null;
			String authHeader = req.getHeader("Authorization");

			if(certarr!=null) {  // If cert !=null, Cert is Tested by Mutual Protocol.
				if(authHeader!=null) { // This is only intended to be a Secure Connection, not an Identity
					for(String auth : Split.split(',',authHeader)) {
						if(auth.startsWith("Bearer ")) { // Bearer = OAuth... Don't use as Authenication
							return new X509HttpTafResp(access, null, "Certificate verified, but Bearer Token is presented", RESP.TRY_ANOTHER_TAF);
						}
					}
				}
				cert = certarr[0];
				responseText = ", validated by Mutual SSL Protocol";
			} else {		 // If cert == null, Get Declared Cert (in header), but validate by having them sign something
				if(authHeader != null) {
					for(String auth : Split.splitTrim(',',authHeader)) {
						if(auth.startsWith("x509 ")) {
							ByteArrayOutputStream baos = new ByteArrayOutputStream(auth.length());
							try {
								array = auth.getBytes();
								ByteArrayInputStream bais = new ByteArrayInputStream(array);
								Symm.base64noSplit.decode(bais, baos, 5);
								certBytes = baos.toByteArray();
								cert = getCert(certBytes);
								
								/** 
								 * Identity from CERT if well know CA and specific encoded information
								 */
								// If found Identity doesn't work, try SignedStuff Protocol
		//									cert.checkValidity();
		//									cert.--- GET FINGERPRINT?
								String stuff = req.getHeader("Signature");
								if(stuff==null) 
									return new X509HttpTafResp(access, null, "Header entry 'Signature' required to validate One way X509 Certificate", RESP.TRY_ANOTHER_TAF);
								String data = req.getHeader("Data"); 
		//									if(data==null) 
		//										return new X509HttpTafResp(access, null, "No signed Data to validate with X509 Certificate", RESP.TRY_ANOTHER_TAF);
		
								// Note: Data Pos shows is "<signatureType> <data>"
		//									int dataPos = (stuff.indexOf(' ')); // determine what is Algorithm
								// Get Signature 
								bais = new ByteArrayInputStream(stuff.getBytes());
								baos = new ByteArrayOutputStream(stuff.length());
								Symm.base64noSplit.decode(bais, baos);
								array = baos.toByteArray();
		//									Signature sig = Signature.getInstance(stuff.substring(0, dataPos)); // get Algorithm from first part of Signature
								
								Signature sig = Signature.getInstance(cert.getSigAlgName()); 
								sig.initVerify(cert.getPublicKey());
								sig.update(data.getBytes());
								if(!sig.verify(array)) {
									access.log(Level.ERROR, "Signature doesn't Match");
									return new X509HttpTafResp(access, null, CERTIFICATE_NOT_VALID_FOR_AUTHENTICATION, RESP.TRY_ANOTHER_TAF);
								}
								responseText = ", validated by Signed Data";
							} catch (Exception e) {
								access.log(e, "Exception while validating Cert");
								return new X509HttpTafResp(access, null, CERTIFICATE_NOT_VALID_FOR_AUTHENTICATION, RESP.TRY_ANOTHER_TAF);
							}
						}
					}
				}
				if(cert==null) {
					return new X509HttpTafResp(access, null, "No Certificate Info on Transaction", RESP.TRY_ANOTHER_TAF);
				}
				
				// A cert has been found, match Identify
				TaggedPrincipal prin=null;
				
				for(int i=0;prin==null && i<certIdents.length;++i) {
					if((prin=certIdents[i].identity(req, cert, certBytes))!=null) {
						responseText = prin.getName() + " matches Certificate " + cert.getSubjectX500Principal().getName() + responseText;
					}
				}
	
				// if Principal is found, check for "AS_USER" and whether this entity is trusted to declare
				if(prin!=null) {
					return new X509HttpTafResp(
						access,
						prin,
						responseText,
						RESP.IS_AUTHENTICATED);
				}
			}
		} catch(Exception e) {
			return new X509HttpTafResp(access, null, e.getMessage(), RESP.TRY_ANOTHER_TAF);	
		}
	
		return new X509HttpTafResp(access, null, "Certificate cannot be used for authentication", RESP.TRY_ANOTHER_TAF);
	}

	@Override
	public Resp revalidate(CachedPrincipal prin, Object state) {
		return null;
	}

	public void add(BasicHttpTaf bht) {
		this.bht = bht;
	}
	
	public CredVal getCredVal(final String key) {
		if(bht==null) {
			return null;
		} else {
			return bht.getCredVal(key);
		}
	}
	
}
