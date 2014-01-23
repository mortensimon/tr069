package com.owera.xaps.tr069.methods;

import java.sql.SQLException;

import com.owera.common.db.NoAvailableConnectionException;
import com.owera.xaps.tr069.HTTPReqResData;

public class GPADecision {
	public static void process(HTTPReqResData reqRes) throws SQLException, NoAvailableConnectionException {
		// Dummy implementation - this decision is not used yet
		reqRes.getResponse().setMethod(TR069Method.EMPTY);
	}

}
