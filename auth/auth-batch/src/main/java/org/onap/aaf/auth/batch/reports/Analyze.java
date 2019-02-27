/**
 * ============LICENSE_START====================================================
 * org.onap.aaf
 * ===========================================================================
 * Copyright (c) 2018 AT&T Intellectual Property. All rights reserved.
 *
 * Modifications Copyright (C) 2019 IBM.
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

package org.onap.aaf.auth.batch.reports;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.onap.aaf.auth.batch.Batch;
import org.onap.aaf.auth.batch.approvalsets.Pending;
import org.onap.aaf.auth.batch.approvalsets.Ticket;
import org.onap.aaf.auth.batch.helpers.Approval;
import org.onap.aaf.auth.batch.helpers.Cred;
import org.onap.aaf.auth.batch.helpers.Cred.Instance;
import org.onap.aaf.auth.batch.helpers.ExpireRange;
import org.onap.aaf.auth.batch.helpers.ExpireRange.Range;
import org.onap.aaf.auth.batch.helpers.Future;
import org.onap.aaf.auth.batch.helpers.Role;
import org.onap.aaf.auth.batch.helpers.UserRole;
import org.onap.aaf.auth.batch.helpers.X509;
import org.onap.aaf.auth.dao.cass.CredDAO;
import org.onap.aaf.auth.dao.cass.UserRoleDAO;
import org.onap.aaf.auth.env.AuthzTrans;
import org.onap.aaf.auth.org.Organization.Identity;
import org.onap.aaf.auth.org.OrganizationException;
import org.onap.aaf.cadi.configure.Factory;
import org.onap.aaf.cadi.util.CSV;
import org.onap.aaf.misc.env.APIException;
import org.onap.aaf.misc.env.Env;
import org.onap.aaf.misc.env.TimeTaken;
import org.onap.aaf.misc.env.Trans;
import org.onap.aaf.misc.env.util.Chrono;


public class Analyze extends Batch {
	private static final int unknown=0;
    private static final int owner=1;
    private static final int supervisor=2;
    private static final int total=0;
    private static final int pending=1;
    private static final int approved=2;
    
    
	private static final String APPROVALS = "Approvals";
	private static final String EXTEND = "Extend";
	private static final String EXPIRED_OWNERS = "ExpiredOwners";
	private static final String CSV = ".csv";
	private static final String INFO = "info";
	private int minOwners;
	private Map<String, CSV.Writer> writerList;
	private ExpireRange expireRange;
	private Date deleteDate;
	private CSV.Writer deleteCW;
	private CSV.Writer approveCW;
	private CSV.Writer extendCW;
	
	public Analyze(AuthzTrans trans) throws APIException, IOException, OrganizationException {
        super(trans.env());
        trans.info().log("Starting Connection Process");
        
        TimeTaken tt0 = trans.start("Cassandra Initialization", Env.SUB);
        try {
            TimeTaken tt = trans.start("Connect to Cluster", Env.REMOTE);
            try {
                session = cluster.connect();
            } finally {
                tt.done();
            }
            
            // Load Cred.  We don't follow Visitor, because we have to gather up everything into Identity Anyway
            Cred.load(trans, session);

            minOwners=1;

            // Create Intermediate Output 
            writerList = new HashMap<>();
            
            expireRange = new ExpireRange(trans.env().access());
            String sdate = Chrono.dateOnlyStamp(expireRange.now);
            for( List<Range> lr : expireRange.ranges.values()) {
            	for(Range r : lr ) {
            		if(writerList.get(r.name())==null) {
                    	File file = new File(logDir(),r.name() + sdate +CSV);
                    	CSV csv = new CSV(env.access(),file);
                    	CSV.Writer cw = csv.writer(false);
                    	cw.row(INFO,r.name(),Chrono.dateOnlyStamp(expireRange.now),r.reportingLevel());
                    	writerList.put(r.name(),cw);
                    	if("Delete".equals(r.name())) {
                    		deleteDate = r.getEnd();
                    		deleteCW = cw;
                    	}
                    	trans.init().log("Creating File:",file.getAbsolutePath());
            		}
            	}
            }
            
            // Setup New Approvals file
            File file = new File(logDir(),APPROVALS + sdate +CSV);
            CSV approveCSV = new CSV(env.access(),file);
            approveCW = approveCSV.writer();
            approveCW.row(INFO,APPROVALS,Chrono.dateOnlyStamp(expireRange.now),1);
            writerList.put(APPROVALS,approveCW);
            
            // Setup Extend Approvals file
            file = new File(logDir(),EXTEND + sdate +CSV);
            CSV extendCSV = new CSV(env.access(),file);
            extendCW = extendCSV.writer();
            extendCW.row(INFO,EXTEND,Chrono.dateOnlyStamp(expireRange.now),1);
            writerList.put(EXTEND,extendCW);
            
            // Load full data of the following
            Approval.load(trans, session, Approval.v2_0_17);
            Role.load(trans, session);
        } finally {
            tt0.done();
        }
    }

    @Override
    protected void run(AuthzTrans trans) {
    	AuthzTrans noAvg = trans.env().newTransNoAvg();
    	
		////////////////////
		final Map<UUID,Ticket> goodTickets = new TreeMap<>();
    	TimeTaken tt = trans.start("Analyze Expired Futures",Trans.SUB);
    	try {
			Future.load(noAvg, session, Future.withConstruct, fut -> {
				List<Approval> appls = Approval.byTicket.get(fut.id());
				if(fut.expires().before(expireRange.now)) {
					deleteCW.comment("Future %s expired", fut.id());
					Future.row(deleteCW,fut);
					if(appls!=null) {
						for(Approval a : appls) {
							Approval.row(deleteCW, a);
						}
					}
				} else if(appls==null) { // Orphaned Future (no Approvals)
					deleteCW.comment("Future is Orphaned");
					Future.row(deleteCW,fut);
				} else  {
					goodTickets.put(fut.fdd.id, new Ticket(fut));
				}
			});
    	} finally {
    		tt.done();
    	}
		
    	tt = trans.start("Connect Approvals with Futures",Trans.SUB);
    	try {
			for(Approval appr : Approval.list) {
				Ticket ticket=null;
				UUID ticketID = appr.getTicket();
				if(ticketID!=null) {
					ticket = goodTickets.get(appr.getTicket());
				}
				if(ticket == null) { // Orphaned Approvals, no Futures
					deleteCW.comment("Approval is Orphaned");
					Approval.row(deleteCW, appr);
				} else {
					ticket.approvals.add(appr); // add to found Ticket
				}
			}
    	} finally {
    		tt.done();
    	}

		/* Run through all Futures, and see if 
		 * 1) they have been executed (no longer valid)
		 * 2) The current Approvals indicate they can proceed 
		 */
		Map<String,Pending> pendingApprs = new HashMap<>();
		Map<String,Pending> pendingTemp = new HashMap<>();

		tt = trans.start("Analyze Good Tickets",Trans.SUB);
		try {
			for(Ticket ticket : goodTickets.values()) {
				pendingTemp.clear();
				switch(ticket.f.target()) {
					case "user_role":
						int state[][] = new int[3][3];
						int type;
								
						for(Approval appr : ticket.approvals) {
							switch(appr.getType()) {
								case "owner":
									type=owner;
									break;
								case "supervisor":
									type=supervisor;
									break;
								default:
									type=0;
							}
							++state[type][total]; // count per type
							switch(appr.getStatus()) {
								case "pending":
									++state[type][pending];
									Pending n = pendingTemp.get(appr.getApprover());
									if(n==null) {
										pendingTemp.put(appr.getApprover(),new Pending(appr.getLast_notified()));
									} else {
										n.inc();
									}
									break;
								case "approved":
									++state[type][approved];
									break;
								default:
									++state[type][unknown];
							}
						}
						
						// To Approve:
						// Always must have at least 1 owner
						if((state[owner][total]>0 && state[owner][approved]>0) &&
							// If there are no Supervisors, that's ok
						    (state[supervisor][total]==0 || 
						    // But if there is a Supervisor, they must have approved 
						    (state[supervisor][approved]>0))) {
								UserRoleDAO.Data urdd = new UserRoleDAO.Data();
								try {
									urdd.reconstitute(ticket.f.fdd.construct);
									if(urdd.expires.before(ticket.f.expires())) {
										extendCW.row("extend_ur",urdd.user,urdd.role,ticket.f.expires());
									}
								} catch (IOException e) {
									trans.error().log("Could not reconstitute UserRole");
								}
						} else { // Load all the Pending.
							for(Entry<String, Pending> es : pendingTemp.entrySet()) {
								Pending p = pendingApprs.get(es.getKey());
								if(p==null) {
									pendingApprs.put(es.getKey(), es.getValue());
								} else {
									p.inc(es.getValue());
								}
							}
						}
						break;
				}
			}
		} finally {
			tt.done();
		}
		
		/**
		 * Decide to Notify about Approvals, based on activity/last Notified
		 */
		tt = trans.start("Analyze Approval Reminders", Trans.SUB);
		try {
			GregorianCalendar gc = new GregorianCalendar();
			gc.add(GregorianCalendar.DAY_OF_WEEK, 5);
			Date remind = gc.getTime();
			
			for(Entry<String, Pending> es : pendingApprs.entrySet()) {
				Pending p = es.getValue();
				if(p.earliest() == null || p.earliest().after(remind)) {
					p.row(approveCW,es.getKey());
				}
			}
		} finally {
			tt.done();
		}
		
		// clear out Approval Intermediates
		goodTickets.clear();
		pendingTemp = null;
		pendingApprs = null;
		
		/**
		   Run through User Roles.  
		   Owners are treated specially in next section.
		   Regular roles are checked against Date Ranges.  If match Date Range, write out to appropriate file.
		*/		
		try {
			tt = trans.start("Analyze UserRoles, storing Owners",Trans.SUB);
			Set<String> specialCommented = new HashSet<>();
			Map<String, Set<UserRole>> owners = new TreeMap<String, Set<UserRole>>();
 			try {
				UserRole.load(noAvg, session, UserRole.v2_0_11, ur -> {
					Identity identity;
					try {
						identity = trans.org().getIdentity(noAvg,ur.user());
						if(identity==null) {
							// Candidate for Delete, but not Users if Special
							String id = ur.user();
							for(String s : specialDomains) {
								if(id.endsWith(s)) {
									if(!specialCommented.contains(id)) {
										deleteCW.comment("ID %s is part of special Domain %s (UR Org Check)", id,s);
										specialCommented.add(id);
									}
									return;
								}
							}
							if(specialNames.contains(id)) {
								if(!specialCommented.contains(id)) {
									deleteCW.comment("ID %s is a special ID  (UR Org Check)", id);
									specialCommented.add(id);
								}
								return;
							}
							ur.row(deleteCW, UserRole.UR,"Not in Organization");
							return;
						} else if(Role.byName.get(ur.role())==null) {
							ur.row(deleteCW, UserRole.UR,String.format("Role %s does not exist", ur.role()));
							return;
						}
						// Cannot just delete owners, unless there is at least one left. Process later
						if ("owner".equals(ur.rname())) {
							Set<UserRole> urs = owners.get(ur.role());
							if (urs == null) {
								urs = new HashSet<UserRole>();
								owners.put(ur.role(), urs);
							}
							urs.add(ur);
						} else {
							Range r = writeAnalysis(noAvg,ur);
							if(r!=null) {
								Approval existing = findApproval(ur);
								if(existing==null) {
									ur.row(approveCW,UserRole.APPROVE_UR);
								}
							}
						}
					} catch (OrganizationException e) {
						noAvg.error().log(e);
					}
				});
 			} finally {
 				tt.done();
 			}
		
			/**
			  Now Process Owners, one owner Role at a time, ensuring one is left,
			  preferably a good one. If so, process the others as normal. 
			  
			  Otherwise, write to ExpiredOwners Report
			*/
 			tt = trans.start("Analyze Owners Separately",Trans.SUB);
 			try {
				if (!owners.values().isEmpty()) {
					File file = new File(logDir(), EXPIRED_OWNERS + Chrono.dateOnlyStamp(expireRange.now) + CSV);
					final CSV ownerCSV = new CSV(env.access(),file);
					CSV.Writer expOwner = ownerCSV.writer();
					expOwner.row(INFO,EXPIRED_OWNERS,Chrono.dateOnlyStamp(expireRange.now),2);

					try {
						for (Set<UserRole> sur : owners.values()) {
							int goodOwners = 0;
							for (UserRole ur : sur) {
								if (ur.expires().after(expireRange.now)) {
									++goodOwners;
								}
							}
	
							for (UserRole ur : sur) {
								if (goodOwners >= minOwners) {
									Range r = writeAnalysis(noAvg, ur);
									if(r!=null) {
										Approval existing = findApproval(ur);
										if(existing==null) {
											ur.row(approveCW,UserRole.APPROVE_UR);
										}
									}
								} else {
									expOwner.row("owner",ur.role(), ur.user(), Chrono.dateOnlyStamp(ur.expires()));
									Approval existing = findApproval(ur);
									if(existing==null) {
										ur.row(approveCW,UserRole.APPROVE_UR);
									}
								}
							}
						}
					} finally {
						if(expOwner!=null) {
							expOwner.close();
						}
					}
				}
 			} finally {
 				tt.done();
 			}
			
			/**
			 * Check for Expired Credentials
			 * 
			 * 
			 */
			tt = trans.start("Analyze Expired Credentials",Trans.SUB);
			try {
				for (Cred cred : Cred.data.values()) {
			    	List<Instance> linst = cred.instances;
			    	if(linst!=null) {
				    	Instance lastBath = null;
				    	for(Instance inst : linst) {
	//			    		if(inst.attn>0) {
	//			    			writeAnalysis(trans, cred, inst);
	//				    		// Special Behavior: only eval the LAST Instance
	//			    		} else 
				    		// All Creds go through Life Cycle
				    		if(deleteDate!=null && inst.expires.before(deleteDate)) {
				        		writeAnalysis(noAvg, cred, inst); // will go to Delete
				        	// Basic Auth has Pre-EOL notifications IF there is no Newer Credential
				    		} else if (inst.type == CredDAO.BASIC_AUTH || inst.type == CredDAO.BASIC_AUTH_SHA256) {
					    		if(lastBath==null || lastBath.expires.before(inst.expires)) {
			    					lastBath = inst;
			    				}
			    			}
				    	}
				    	if(lastBath!=null) {
				    		writeAnalysis(noAvg, cred, lastBath);
				    	}
			    	}
				}
			} finally {
				tt.done();
			}

			////////////////////
			tt = trans.start("Analyze Expired X509s",Trans.SUB);
			try {
				X509.load(noAvg, session, x509 -> {
					try {
						for(Certificate cert : Factory.toX509Certificate(x509.x509)) {
							writeAnalysis(noAvg, x509, (X509Certificate)cert);
						}
					} catch (CertificateException | IOException e) {
						noAvg.error().log(e, "Error Decrypting X509");
					}
	
				});
			} finally {
				tt.done();
			}
		} catch (FileNotFoundException e) {
			noAvg.info().log(e);
		}
	}
 
	private Approval findApproval(UserRole ur) {
		Approval existing = null;
		List<Approval> apprs = Approval.byUser.get(ur.user());
		if(apprs!=null) {
			for(Approval appr : apprs) {
				if(ur.role().equals(appr.getRole()) &&
					appr.getMemo().contains(Chrono.dateOnlyStamp(ur.expires()))) {
						existing = appr; 
				}
			}
		}
		return existing;
	}

	private Range writeAnalysis(AuthzTrans trans, UserRole ur) {
		Range r = expireRange.getRange("ur", ur.expires());
		if(r!=null) {
			CSV.Writer cw = writerList.get(r.name());
			if(cw!=null) {
				ur.row(cw,UserRole.UR);
			}
		}
		return r;
	}
    
    private void writeAnalysis(AuthzTrans trans, Cred cred, Instance inst) {
    	if(cred!=null && inst!=null) {
			Range r = expireRange.getRange("cred", inst.expires);
			if(r!=null) {
				CSV.Writer cw = writerList.get(r.name());
				if(cw!=null) {
					cred.row(cw,inst);
				}
			}
    	}
	}

    private void writeAnalysis(AuthzTrans trans, X509 x509, X509Certificate x509Cert) throws IOException {
		Range r = expireRange.getRange("x509", x509Cert.getNotAfter());
		if(r!=null) {
			CSV.Writer cw = writerList.get(r.name());
			if(cw!=null) {
				x509.row(cw,x509Cert);
			}
		}
	}
    
    @Override
    protected void _close(AuthzTrans trans) {
        session.close();
    	for(CSV.Writer cw : writerList.values()) {
    		cw.close();
    	}
    }

}