package com.taobao.arthas.core;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import com.taobao.arthas.common.AnsiLog;
import com.taobao.arthas.common.ArthasConstants;
import com.taobao.arthas.common.JavaVersionUtils;
import com.taobao.arthas.core.config.Configure;
import com.taobao.middleware.cli.CLI;
import com.taobao.middleware.cli.CLIs;
import com.taobao.middleware.cli.CommandLine;
import com.taobao.middleware.cli.Option;
import com.taobao.middleware.cli.TypedOption;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Properties;

/**
 * Arthas启动器
 */
public class Arthas {

    private Arthas(String[] args) throws Exception {
        attachAgent(parse(args));
    }

    private Configure parse(String[] args) {
        Option pid = new TypedOption<Long>().setType(Long.class).setShortName("pid").setRequired(true);
        Option core = new TypedOption<String>().setType(String.class).setShortName("core").setRequired(true);
        Option agent = new TypedOption<String>().setType(String.class).setShortName("agent").setRequired(true);
        Option target = new TypedOption<String>().setType(String.class).setShortName("target-ip");
        Option telnetPort = new TypedOption<Integer>().setType(Integer.class)
                .setShortName("telnet-port");
        Option httpPort = new TypedOption<Integer>().setType(Integer.class)
                .setShortName("http-port");
        Option sessionTimeout = new TypedOption<Integer>().setType(Integer.class)
                        .setShortName("session-timeout");

        Option username = new TypedOption<String>().setType(String.class).setShortName("username");
        Option password = new TypedOption<String>().setType(String.class).setShortName("password");

        Option tunnelServer = new TypedOption<String>().setType(String.class).setShortName("tunnel-server");
        Option agentId = new TypedOption<String>().setType(String.class).setShortName("agent-id");
        Option appName = new TypedOption<String>().setType(String.class).setShortName(ArthasConstants.APP_NAME);

        Option statUrl = new TypedOption<String>().setType(String.class).setShortName("stat-url");
        Option disabledCommands = new TypedOption<String>().setType(String.class).setShortName("disabled-commands");

        CLI cli = CLIs.create("arthas").addOption(pid).addOption(core).addOption(agent).addOption(target)
                .addOption(telnetPort).addOption(httpPort).addOption(sessionTimeout)
                .addOption(username).addOption(password)
                .addOption(tunnelServer).addOption(agentId).addOption(appName).addOption(statUrl).addOption(disabledCommands);
        CommandLine commandLine = cli.parse(Arrays.asList(args));

        Configure configure = new Configure();
        configure.setJavaPid((Long) commandLine.getOptionValue("pid"));
        configure.setArthasAgent((String) commandLine.getOptionValue("agent"));
        configure.setArthasCore((String) commandLine.getOptionValue("core"));
        if (commandLine.getOptionValue("session-timeout") != null) {
            configure.setSessionTimeout((Integer) commandLine.getOptionValue("session-timeout"));
        }

        if (commandLine.getOptionValue("target-ip") != null) {
            configure.setIp((String) commandLine.getOptionValue("target-ip"));
        }

        if (commandLine.getOptionValue("telnet-port") != null) {
            configure.setTelnetPort((Integer) commandLine.getOptionValue("telnet-port"));
        }
        if (commandLine.getOptionValue("http-port") != null) {
            configure.setHttpPort((Integer) commandLine.getOptionValue("http-port"));
        }

        configure.setUsername((String) commandLine.getOptionValue("username"));
        configure.setPassword((String) commandLine.getOptionValue("password"));

        configure.setTunnelServer((String) commandLine.getOptionValue("tunnel-server"));
        configure.setAgentId((String) commandLine.getOptionValue("agent-id"));
        configure.setStatUrl((String) commandLine.getOptionValue("stat-url"));
        configure.setDisabledCommands((String) commandLine.getOptionValue("disabled-commands"));
        configure.setAppName((String) commandLine.getOptionValue(ArthasConstants.APP_NAME));
        return configure;
    }

    /**
     * 将arthas-agent attach到目标JVM
     * @param configure
     * @throws Exception
     */
    private void attachAgent(Configure configure) throws Exception {
        VirtualMachineDescriptor virtualMachineDescriptor = null;
        for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
            String pid = descriptor.id();
            if (pid.equals(Long.toString(configure.getJavaPid()))) {
                virtualMachineDescriptor = descriptor;
                break;
            }
        }
        /*
         * virtualMachine#attach到目标进程，并加载arthas-agent.jar作为agent jar包。
         */
        VirtualMachine virtualMachine = null;
        try {
            if (null == virtualMachineDescriptor) { // 使用 attach(String pid) 这种方式
                virtualMachine = VirtualMachine.attach("" + configure.getJavaPid());
            } else {
                virtualMachine = VirtualMachine.attach(virtualMachineDescriptor);
            }

            Properties targetSystemProperties = virtualMachine.getSystemProperties();
            String targetJavaVersion = JavaVersionUtils.javaVersionStr(targetSystemProperties);
            String currentJavaVersion = JavaVersionUtils.javaVersionStr();
            // 检查目标java版本和当前进程的java版本，如果不相等提发出警告，可能出现异常
            if (targetJavaVersion != null && currentJavaVersion != null) {
                if (!targetJavaVersion.equals(currentJavaVersion)) {
                    AnsiLog.warn("Current VM java version: {} do not match target VM java version: {}, attach may fail.",
                                    currentJavaVersion, targetJavaVersion);
                    AnsiLog.warn("Target VM JAVA_HOME is {}, arthas-boot JAVA_HOME is {}, try to set the same JAVA_HOME.",
                                    targetSystemProperties.getProperty("java.home"), System.getProperty("java.home"));
                }
            }

            String arthasAgentPath = configure.getArthasAgent();
            //convert jar path to unicode string
            configure.setArthasAgent(encodeArg(arthasAgentPath));
            configure.setArthasCore(encodeArg(configure.getArthasCore()));
            try {
                // 通过agentmain方式（在进程启动之后attach上去）加载arthas-agent到目标JVM
                // 当然正常使用也可以通过premain方式（在目标进程启动之前，通过-agent参数静态指定）加载
                /*
                 *其中arthas-agent.jar通过pom指定以下build参数
                 *          <archive>
                                <manifestEntries>
                                    <Premain-Class>com.taobao.arthas.agent334.AgentBootstrap</Premain-Class>
                                    <Agent-Class>com.taobao.arthas.agent334.AgentBootstrap</Agent-Class>
                                    <Can-Redefine-Classes>true</Can-Redefine-Classes>
                                    <Can-Retransform-Classes>true</Can-Retransform-Classes>
                                    <Specification-Title>${project.name}</Specification-Title>
                                    <Specification-Version>${project.version}</Specification-Version>
                                    <Implementation-Title>${project.name}</Implementation-Title>
                                    <Implementation-Version>${project.version}</Implementation-Version>
                                </manifestEntries>
                            </archive>
                 */
                virtualMachine.loadAgent(arthasAgentPath,
                        configure.getArthasCore() + ";" + configure.toString());
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("Non-numeric value found")) {
                    AnsiLog.warn(e);
                    AnsiLog.warn("It seems to use the lower version of JDK to attach the higher version of JDK.");
                    AnsiLog.warn(
                            "This error message can be ignored, the attach may have been successful, and it will still try to connect.");
                } else {
                    throw e;
                }
            }
        } finally {
            if (null != virtualMachine) {
                virtualMachine.detach();
            }
        }
    }

    private static String encodeArg(String arg) {
        try {
            return URLEncoder.encode(arg, "utf-8");
        } catch (UnsupportedEncodingException e) {
            return arg;
        }
    }

    public static void main(String[] args) {
        try {
            new Arthas(args);
        } catch (Throwable t) {
            AnsiLog.error("Start arthas failed, exception stack trace: ");
            t.printStackTrace();
            System.exit(-1);
        }
    }
}
