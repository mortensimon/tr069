package com.owera.xaps.tr069.decision.shelljob;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.owera.common.db.NoAvailableConnectionException;
import com.owera.common.util.Cache;
import com.owera.common.util.CacheValue;
import com.owera.xaps.base.Log;
import com.owera.xaps.base.UnitJob;
import com.owera.xaps.base.db.DBAccess;
import com.owera.xaps.base.db.DBAccessSessionTR069;
import com.owera.xaps.dbi.Job;
import com.owera.xaps.dbi.ScriptExecution;
import com.owera.xaps.dbi.ScriptExecutions;
import com.owera.xaps.dbi.Unit;
import com.owera.xaps.dbi.UnitJobStatus;
import com.owera.xaps.dbi.UnitParameter;
import com.owera.xaps.dbi.UnittypeParameter;
import com.owera.xaps.dbi.UnittypeParameters;
import com.owera.xaps.dbi.XAPS;
import com.owera.xaps.dbi.XAPSUnit;
import com.owera.xaps.dbi.util.SystemParameters;
import com.owera.xaps.tr069.CPEParameters;
import com.owera.xaps.tr069.Provisioning;
import com.owera.xaps.tr069.HTTPReqResData;
import com.owera.xaps.tr069.SessionData;
import com.owera.xaps.tr069.exception.TR069ExceptionShortMessage;
import com.owera.xaps.tr069.exception.TR069DatabaseException;
import com.owera.xaps.tr069.exception.TR069Exception;
import com.owera.xaps.tr069.methods.GPVDecision;
import com.owera.xaps.tr069.xml.ParameterList;
import com.owera.xaps.tr069.xml.ParameterValueStruct;

/**
 * This class performs a SHELL-job
 */
public class ShellJobLogic {

	private static Random random = new Random();

	/**
	 * We need a monitor to synchronize, so that two devices using the same
	 * unit-id (same ACS-username), do not run the shell-script on the same
	 * context at the same time. To prevent this we use the unit-id is used to
	 * lookup a simple Object(). We cannot use the unit-id String object directly,
	 * because even if the String-object encapsulates the same string, it is not
	 * the same object.
	 */
	private static Cache monitorCache = new Cache();

	/**
	 * @param sessionData
	 * @param job
	 * @param uj
	 * @throws TR069DatabaseException 
	 * @throws SQLException
	 * @throws NoAvailableConnectionException
	 */
	public static void execute(HTTPReqResData reqRes, SessionData sessionData, Job job, UnitJob uj) throws TR069Exception {
		String unitId = sessionData.getUnitId();
		CacheValue cv = monitorCache.get(unitId);
		if (cv == null) {
			cv = new CacheValue(new Object()); // default settings: session-timeout for 30 minutes
			monitorCache.put(unitId, cv);
		}
		synchronized (cv.getObject()) {
			// read parameters from device and save it to the unit
			ShellJobLogic.importReadOnlyParameters(sessionData);
			// execute changes using the shell-script, all changes are written to database
			ShellJobLogic.executeShellScript(reqRes, sessionData, job, uj);
			// read the changes from the database and send to CPE
			ShellJobLogic.prepareSPV(sessionData, job);
		}
	}

	/**
	 * Responsible for executing a shell script. The following tasks must be done
	 * <p/>
	 * 1. Retrieve shell script from job
	 * 2. Retrieve shell daemon. If necessary start new shell daemon.
	 * If not allowed to make more daemons and waiting for more than 10 seconds, abort - should result in Job verification fail (not sure how)
	 * 3. Feed shell script into shell daemon. Wait for the script to be executed.
	 * If shell daemon returns error - should result in Job verification fail (not sure how)
	 *
	 * @param job
	 * @throws TR069DatabaseException 
	 * @throws
	 */
	private static void executeShellScript(HTTPReqResData reqRes, SessionData sessionData, Job job, UnitJob uj) throws TR069Exception {
		ScriptExecutions executions = Provisioning.getExecutions();
		String scriptArgs = "\"-uut:" + sessionData.getUnittype().getName() + "/pr:" + sessionData.getProfile().getName() + "/un:" + sessionData.getUnitId() + "\"";
		String requestId = "JOB:" + job.getId() + ":" + random.nextInt(1000000); // should be a unique Id
		try {
			executions.requestExecution(job.getFile(), scriptArgs, requestId);
		} catch (SQLException e) {
			throw new TR069DatabaseException("Could not request a script execution", e);
		}
		long timeWaited = 0;
		long timeWaitFactor = 4;
		while (true) {
			try {
				long timeWait = timeWaitFactor * timeWaitFactor * timeWaitFactor; // will wait longer and longer 
				Thread.sleep(timeWait);
				timeWaited += timeWait;
				timeWaitFactor += 2;
				ScriptExecution se = executions.getExecution(sessionData.getUnittype(), requestId);
				if (se.getExitStatus() != null) {
					if (se.getExitStatus() == true) { // ERROR OCCURRED
						Log.error(ShellJobLogic.class, se.getErrorMessage());
						uj.stop(UnitJobStatus.CONFIRMED_FAILED);
					} else
						uj.stop(UnitJobStatus.COMPLETED_OK);
					break;
				}
				if (timeWaited > 30000) {
					Log.error(ShellJobLogic.class, "The execution of the shell script did not complete within 30 sec");
					uj.stop(UnitJobStatus.CONFIRMED_FAILED);
					break;
				}
			} catch (Throwable t) {
				throw new TR069Exception("An error occurred in the scheduling/registration of a shell-script", TR069ExceptionShortMessage.MISC, t);
			}
		}
	}

	/**
	 * Read unit parameters from database, to see if any changes
	 * have occurred (during the shell script execution). If ReadWrite
	 * parameters differ from CPE, then send them to the CPE.
	 *
	 * @param sessionData
	 * @throws TR069DatabaseException 
	 */
	private static void toCPE(SessionData sessionData) throws TR069DatabaseException {
		UnittypeParameters utps = sessionData.getUnittype().getUnittypeParameters();
		XAPS xaps = sessionData.getDbAccess().getXaps();
		Unit unit;
		try {
			XAPSUnit xapsUnit = DBAccess.getXAPSUnit(xaps);
			unit = xapsUnit.getUnitById(sessionData.getUnitId());
		} catch (SQLException e) {
			throw new TR069DatabaseException(e);
		}
		ParameterList toCPE = new ParameterList();
		for (int i = 0; i < sessionData.getFromCPE().size(); i++) {
			ParameterValueStruct pvsCPE = sessionData.getFromCPE().get(i);
			if (pvsCPE == null || pvsCPE.getValue() == null || pvsCPE.getName() == null)
				continue;
			UnittypeParameter utp = utps.getByName(pvsCPE.getName());
			if (utp == null || !utp.getFlag().isReadWrite())
				continue;
			UnitParameter up = unit.getUnitParameters().get(utp.getName());
			if (up == null || up.getValue() == null)
				continue;
			if (!up.getValue().equals(pvsCPE.getValue())) {
				if (pvsCPE.getType() != null) {
					toCPE.addParameterValueStruct(new ParameterValueStruct(utp.getName(), up.getValue(), pvsCPE.getType()));
				} else {
					toCPE.addParameterValueStruct(new ParameterValueStruct(utp.getName(), up.getValue()));
				}
			}
		}
		sessionData.setToCPE(toCPE);
	}

	/**
	 * In order for the shell script to run with the correct parameters,
	 * we must read them from the device and write it to the database, before
	 * the script starts.
	 *
	 * @param sessionData
	 * @throws TR069DatabaseException 
	 */
	private static void importReadOnlyParameters(SessionData sessionData) throws TR069DatabaseException {
		//		List<ParameterValueStruct> toDB = new ArrayList<ParameterValueStruct>();
		List<UnitParameter> unitParameters = new ArrayList<UnitParameter>();
		UnittypeParameters utps = sessionData.getUnittype().getUnittypeParameters();
		for (int i = 0; i < sessionData.getFromCPE().size(); i++) {
			ParameterValueStruct pvsCPE = sessionData.getFromCPE().get(i);
			if (pvsCPE == null || pvsCPE.getValue() == null || pvsCPE.getName() == null)
				continue;
			UnittypeParameter utp = utps.getByName(pvsCPE.getName());
			if (utp == null || !utp.getFlag().isReadOnly())
				continue;
			ParameterValueStruct pvsDB = (ParameterValueStruct) sessionData.getFromDB().get(pvsCPE.getName());
			/* Make sure that all AlwaysRead-params and all populated Read-params are written to DB here. This
			 * Will make sure DB has the right state when the script is executed in the next step. 
			 */
			if (utp.getFlag().isAlwaysRead())
				unitParameters.add(new UnitParameter(utp, sessionData.getUnitId(), pvsCPE.getValue(), sessionData.getProfile()));
			//				toDB.add(pvsCPE);
			else if (pvsDB != null && pvsDB.getValue() != null)
				unitParameters.add(new UnitParameter(utp, sessionData.getUnitId(), pvsCPE.getValue(), sessionData.getProfile()));
			//				toDB.add(pvsCPE);
		}
		if (unitParameters.size() > 0) {
			try {
				XAPS xaps = DBAccess.getDBI().getXaps();
				XAPSUnit xapsUnit = DBAccess.getXAPSUnit(xaps);
				xapsUnit.addOrChangeUnitParameters(unitParameters, sessionData.getProfile());
			} catch (SQLException sqle) {
				throw new TR069DatabaseException(sqle);
			}
			//			sessionData.setToDB(toDB);
			//			DBAccessSessionTR069 dbAccessSessionTR069 = new DBAccessSessionTR069(DBAccess.getDBI(), sessionData.getDbAccess());
			//			dbAccessSessionTR069.writeValueMap(sessionData);
		}
	}

	private static void prepareSPV(SessionData sessionData, Job job) throws TR069DatabaseException {
		toCPE(sessionData);
		List<ParameterValueStruct> toDB = new ArrayList<ParameterValueStruct>();
		sessionData.setToDB(toDB);
		CPEParameters cpeParams = sessionData.getCpeParameters();
		String PII = cpeParams.PERIODIC_INFORM_INTERVAL;
		String nextPII = "" + sessionData.getPIIDecision().nextPII();
		sessionData.getProvisioningMessage().setPeriodicInformInterval(new Integer(nextPII));
		sessionData.getToCPE().addOrChangeParameterValueStruct(PII, nextPII, "xsd:unsignedInt");
		Log.debug(GPVDecision.class, "-ACS->CPE      " + PII + " CPE[" + nextPII + "] ACS[" + nextPII + "] Decided by ACS");
		sessionData.getToDB().add(new ParameterValueStruct(PII, "" + nextPII));
		Log.debug(GPVDecision.class, "-ACS->ACS      " + PII + " CPE[" + nextPII + "] ACS[" + nextPII + "] Decided by ACS");
		sessionData.getToDB().add(new ParameterValueStruct(SystemParameters.PERIODIC_INTERVAL, "" + nextPII));
		Log.debug(GPVDecision.class, "-ACS->ACS      " + SystemParameters.PERIODIC_INTERVAL + " CPE[" + nextPII + "] ACS[" + nextPII + "] Decided by ACS");
		//		DBAccessSessionTR069 dbAccessSessionTR069 = new DBAccessSessionTR069(DBAccess.getDBI(), sessionData.getDbAccess());
		DBAccessSessionTR069.writeUnitParams(sessionData);
	}

}
