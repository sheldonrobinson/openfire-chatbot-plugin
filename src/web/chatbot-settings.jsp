<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ page import="java.util.*"  errorPage="error.jsp" %>
<%@ page import="org.jivesoftware.openfire.*,org.jivesoftware.util.*" %>
<%@ page import="ia.konnekted.konstrukt.ofkhatbot.OllamaChatbotPlugin" %>
<%@ page import="ia.konnekted.konstrukt.ofkhatbot.Constants" %>
<%@ page import="org.slf4j.Logger, org.slf4j.LoggerFactory" %>
<%@ taglib uri="admin" prefix="admin" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c"%>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt"%>

<%
    Logger log = LoggerFactory.getLogger("chatbot-settings-jsp");

    boolean update = request.getParameter("update") != null;

    if (update)
    {
        String alias = ParamUtils.getStringParameter(request, "alias",Constants.CHATBOT_ALIAS_DEFAULT);
        JiveGlobals.setProperty("chatbot.alias", alias);
				
        String host_url = ParamUtils.getStringParameter(request, "host_url", Constants.CHATBOT_HOST_URL_DEFAULT);
        JiveGlobals.setProperty("chatbot.host.url", host_url);

        String model_name = ParamUtils.getStringParameter(request, "model_name", Constants.CHATBOT_LLM_MODEL_DEFAULT);
        JiveGlobals.setProperty("chatbot.llm.model", model_name);

        String format = ParamUtils.getStringParameter(request, "format", Constants.CHATBOT_LLM_FORMAT_DEFAULT);
        JiveGlobals.setProperty("chatbot.llm.format", format);

        String system_prompt = ParamUtils.getStringParameter(request, "system_prompt", Constants.CHATBOT_SYSTEM_PROMPT_DEFAULT);
        JiveGlobals.setProperty("chatbot.system.prompt", system_prompt);

        double temperature = ParamUtils.getDoubleParameter(request, "temperature", Constants.CHATBOT_LLM_TEMPERATURE_DEFAULT);
        JiveGlobals.setProperty("chatbot.llm.temperature", String.valueOf(temperature));

        int top_k_sampling = ParamUtils.getIntParameter(request, "top_k_sampling", Constants.CHATBOT_LLM_TOP_K_DEFAULT);
        JiveGlobals.setProperty("chatbot.llm.top.k.sampling", String.valueOf(top_k_sampling));
        
        double top_p_sampling = ParamUtils.getDoubleParameter(request, "top_p_sampling", Constants.CHATBOT_LLM_TOP_P_DEFAULT);
        JiveGlobals.setProperty("chatbot.llm.top.p.sampling", String.valueOf(top_p_sampling));

        double repeat_penalty = ParamUtils.getDoubleParameter(request, "repeat_penalty", Constants.CHATBOT_LLM_REPEAT_PENALTY_DEFAULT);
        JiveGlobals.setProperty("chatbot.llm.top.p.sampling", String.valueOf(repeat_penalty));
        
        int predictions = ParamUtils.getIntParameter(request, "predictions", Constants.CHATBOT_LLM_PREDICTIONS_DEFAULT);
        JiveGlobals.setProperty("chatbot.llm.predictions", String.valueOf(predictions));

        boolean enabled = ParamUtils.getBooleanParameter(request, "enabled", true);
        JiveGlobals.setProperty("chatbot.enabled", String.valueOf(enabled));
    }


%>
<html>
<head>
   <title><fmt:message key="config.page.settings" /></title>
   <meta name="pageID" content="chatbot-settings"/>
</head>
<body>
<admin:FlashMessage/>
<div class="jive-table">
<form action="chatbot-settings.jsp" method="post">
    <input name="csrf" value="<c:out value="${csrf}"/>" type="hidden" />
    <p>
        <table class="jive-table" cellpadding="0" cellspacing="0" border="0" width="100%">
            <thead> 
                <tr>
                    <th colspan="2"><fmt:message key="config.page.settings.description"/></th>
                </tr>
            </thead>
            <tbody> 
                <tr>
                    <td nowrap  colspan="2">
                        <input type="checkbox" name="enabled" <%= (JiveGlobals.getProperty("chatbot.enabled", "true").equals("true")) ? "checked" : "" %>>
                        <fmt:message key="config.page.configuration.enabled" />
                    </td>
                </tr>
                <tr>
                    <td align="left" width="150">
                        <fmt:message key="config.page.configuration.host.url"/>
                    </td>
                    <td><input type="text" size="100" maxlength="256" name="host_url" required
                           value="<%= JiveGlobals.getProperty("chatbot.host.url", Constants.CHATBOT_HOST_URL_DEFAULT) %>">
                    </td>
                </tr>
                <tr>
                    <td align="left" width="150">
                        <fmt:message key="config.page.configuration.alias"/>
                    </td>
                    <td><input type="text" size="50" maxlength="100" name="alias" required
                           value="<%= JiveGlobals.getProperty("chatbot.alias", Constants.CHATBOT_ALIAS_DEFAULT) %>" />
                    </td>
                </tr>
                <tr>
                    <td align="left" width="150">
                        <fmt:message key="config.page.configuration.model.name"/>
                    </td>
                    <td><input type="text" size="100" maxlength="256" name="model_name" required
                           value="<%= JiveGlobals.getProperty("chatbot.llm.model", Constants.CHATBOT_LLM_MODEL_DEFAULT) %>">
                    </td>
                </tr>
                <tr>
                    <td align="left" width="150">
                        <fmt:message key="config.page.configuration.format"/>
                    </td>
                    <td><input type="text" size="100" maxlength="256" name="format" required
                           value="<%= JiveGlobals.getProperty("chatbot.llm.format", Constants.CHATBOT_LLM_FORMAT_DEFAULT) %>">
                    </td>
                </tr>
                <tr>
                    <td nowrap  colspan="2">
                        <hr />
                    </td>
                </tr>
                <tr>
                    <td align="left" width="150">
                        <fmt:message key="config.page.configuration.temperature"/>
                    </td>
                    <td><input type="text" size="20" name="temperature" required
                           value="<%= JiveGlobals.getProperty("chatbot.llm.temperature", String.valueOf(Constants.CHATBOT_LLM_TEMPERATURE_DEFAULT)) %>" />
                    </td>
                </tr>
                <tr>
                    <td align="left" width="150">
                        <fmt:message key="config.page.configuration.top.k.sampling"/>
                    </td>
                    <td><input type="text" size="20" name="top_k_sampling" required
                           value="<%= JiveGlobals.getProperty("chatbot.llm.top.k.sampling", String.valueOf(Constants.CHATBOT_LLM_TOP_K_DEFAULT)) %>" />
                    </td>
                </tr>
                <tr>
                    <td align="left" width="150">
                        <fmt:message key="config.page.configuration.top.p.sampling"/>
                    </td>
                    <td><input type="text" size="20" name="top_p_sampling" required
                           value="<%= JiveGlobals.getProperty("chatbot.llm.top.p.sampling", String.valueOf(Constants.CHATBOT_LLM_TOP_P_DEFAULT)) %>" />
                    </td>
                </tr>
                <tr>
                    <td align="left" width="150">
                        <fmt:message key="config.page.configuration.predictions"/>
                    </td>
                    <td><input type="text" size="20" name="predictions" required
                           value="<%= JiveGlobals.getProperty("chatbot.llm.predictions", String.valueOf(Constants.CHATBOT_LLM_PREDICTIONS_DEFAULT)) %>" />
                    </td>
                </tr>
                <tr>
                    <td align="left" width="150">
                        <fmt:message key="config.page.configuration.repeat.penalty"/>
                    </td>
                    <td><input type="text" size="20" name="repeat_penalty" required
                           value="<%= JiveGlobals.getProperty("chatbot.llm.repeat.penalty", String.valueOf(Constants.CHATBOT_LLM_REPEAT_PENALTY_DEFAULT)) %>" />
                    </td>
                </tr>

                <tr>
                    <td align="left" width="150">
                        <fmt:message key="config.page.configuration.system.prompt"/>
                    </td>
                    <td>
                    <textarea rows="10" cols="80" name="system_prompt"><%= JiveGlobals.getProperty("chatbot.system.prompt", Constants.CHATBOT_SYSTEM_PROMPT_DEFAULT) %></textarea>
                    </td>
                </tr>
            </tbody>
        </table>
    </p>
    <p>
        <table class="jive-table" cellpadding="0" cellspacing="0" border="0" width="100%">
            <thead>
                <tr>
                    <th colspan="2"><fmt:message key="config.page.configuration.save.title"/></th>
                </tr>
            </thead>
            <tbody>
                <tr>
                    <th colspan="2"><input type="submit" name="update" value="<fmt:message key="config.page.configuration.submit" />">&nbsp;&nbsp;<fmt:message key="config.page.configuration.restart.warning"/></th>
                </tr>
            </tbody>
        </table>
    </p>
</form>
</div>
</body>
</html>
