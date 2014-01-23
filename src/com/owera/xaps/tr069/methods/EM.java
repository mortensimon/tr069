package com.owera.xaps.tr069.methods;

import java.sql.SQLException;

import com.owera.common.db.NoAvailableConnectionException;
import com.owera.xaps.tr069.HTTPReqResData;

public class EM {

	public static void process(HTTPReqResData reqRes) throws SQLException, NoAvailableConnectionException {
		reqRes.getRequest().setMethod(TR069Method.EMPTY);
	}
}
