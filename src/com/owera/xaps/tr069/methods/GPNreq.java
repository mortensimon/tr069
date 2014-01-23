package com.owera.xaps.tr069.methods;

import com.owera.xaps.tr069.xml.Body;

public class GPNreq extends Body {

	private static final String START = "\t\t<cwmp:GetParameterNames>\n";
	private static final String PARAMETER_PATH_START = "\t\t\t<ParameterPath>";
	private static final String PARAMETER_PATH_END = "</ParameterPath>\n";
	private static final String NEXTLEVEL = "\t\t\t<NextLevel>false</NextLevel>\n";
//	private static final String NEXTLEVEL = "\t\t\t<NextLevel>true</NextLevel>\n";
	private static final String END = "\t\t</cwmp:GetParameterNames>\n";

	private String parameter;

	public GPNreq(String parameter) {
		this.parameter = parameter;
	}

	@Override
	public String toXmlImpl() {
		StringBuilder sb = new StringBuilder(3);
		sb.append(START);
		sb.append(PARAMETER_PATH_START);
		sb.append(parameter);
		sb.append(PARAMETER_PATH_END);
		sb.append(NEXTLEVEL);
		sb.append(END);
		return sb.toString();
	}

}
