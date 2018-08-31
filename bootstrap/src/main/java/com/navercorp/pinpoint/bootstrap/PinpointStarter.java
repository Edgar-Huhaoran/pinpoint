/*
 * Copyright 2014 NAVER Corp.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.navercorp.pinpoint.bootstrap;

import com.navercorp.pinpoint.ProductInfo;
import com.navercorp.pinpoint.bootstrap.config.DefaultProfilerConfig;
import com.navercorp.pinpoint.bootstrap.config.ProfilerConfig;
import com.navercorp.pinpoint.common.Version;
import com.navercorp.pinpoint.common.service.AnnotationKeyRegistryService;
import com.navercorp.pinpoint.common.service.DefaultAnnotationKeyRegistryService;
import com.navercorp.pinpoint.common.service.DefaultServiceTypeRegistryService;
import com.navercorp.pinpoint.common.service.DefaultTraceMetadataLoaderService;
import com.navercorp.pinpoint.common.service.ServiceTypeRegistryService;
import com.navercorp.pinpoint.common.service.TraceMetadataLoaderService;
import com.navercorp.pinpoint.common.util.PinpointThreadFactory;
import com.navercorp.pinpoint.common.util.SimpleProperty;
import com.navercorp.pinpoint.common.util.SystemProperty;
import com.navercorp.pinpoint.common.util.logger.CommonLoggerFactory;
import com.navercorp.pinpoint.common.util.logger.StdoutCommonLoggerFactory;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author Jongho Moon
 *
 */
class PinpointStarter {

    private final BootLogger logger = BootLogger.getLogger(PinpointStarter.class.getName());

    public static final String AGENT_TYPE = "AGENT_TYPE";

    public static final String DEFAULT_AGENT = "DEFAULT_AGENT";
    public static final String BOOT_CLASS = "com.navercorp.pinpoint.profiler.DefaultAgent";

    public static final String PLUGIN_TEST_AGENT = "PLUGIN_TEST";
    public static final String PLUGIN_TEST_BOOT_CLASS = "com.navercorp.pinpoint.test.PluginTestAgent";

    private SimpleProperty systemProperty = SystemProperty.INSTANCE;

    private final Map<String, String> agentArgs;
    private final BootstrapJarFile bootstrapJarFile;
    private final ClassPathResolver classPathResolver;
    private final Instrumentation instrumentation;


    public PinpointStarter(Map<String, String> agentArgs, BootstrapJarFile bootstrapJarFile, ClassPathResolver classPathResolver, Instrumentation instrumentation) {
        if (agentArgs == null) {
            throw new NullPointerException("agentArgs must not be null");
        }
        if (bootstrapJarFile == null) {
            throw new NullPointerException("bootstrapJarFile must not be null");
        }
        if (classPathResolver == null) {
            throw new NullPointerException("classPathResolver must not be null");
        }
        if (instrumentation == null) {
            throw new NullPointerException("instrumentation must not be null");
        }
        this.agentArgs = agentArgs;
        this.bootstrapJarFile = bootstrapJarFile;
        this.classPathResolver = classPathResolver;
        this.instrumentation = instrumentation;

    }

    boolean start() {
        /**
         * 当前JVM的ID信息获取
         */
        final IdValidator idValidator = new IdValidator();
        final String agentId = idValidator.getAgentId(); // 通过JVM启动时配置的参数，获取当前JVM的agentId
        if (agentId == null) {
            return false;
        }
        final String applicationName = idValidator.getApplicationName(); // 通过JVM启动时配置的参数，获取当前JVM的applicationName
        if (applicationName == null) {
            return false;
        }


        /**
         * 参数处理、对象初始化等工作
         */
        // 获取plugin目录下的.jar文件，也就是所有的plugin的jar包地址
        URL[] pluginJars = classPathResolver.resolvePlugins();

        // TODO using PLogger instead of CommonLogger
        CommonLoggerFactory loggerFactory = StdoutCommonLoggerFactory.INSTANCE;
        TraceMetadataLoaderService typeLoaderService = new DefaultTraceMetadataLoaderService(pluginJars, loggerFactory);
        ServiceTypeRegistryService serviceTypeRegistryService = new DefaultServiceTypeRegistryService(typeLoaderService, loggerFactory);
        AnnotationKeyRegistryService annotationKeyRegistryService = new DefaultAnnotationKeyRegistryService(typeLoaderService, loggerFactory);

        // 获取pinpoint.config文件
        String configPath = getConfigPath(classPathResolver);
        if (configPath == null) {
            return false;
        }

        // 将当前文件所在目录下的log文件作为日志输出文件，并且将其写入到System参数中
        // set the path of log file as a system property
        saveLogFilePath(classPathResolver);

        // 将当前pinpoint版本作为参数写入到System参数中
        savePinpointVersion();


        try {
            // 通过前面获取的配置地址加载配置信息，后面可以直接通过profilerConfig对象获取agent的配置
            // Is it right to load the configuration in the bootstrap?
            ProfilerConfig profilerConfig = DefaultProfilerConfig.load(configPath);

            /**
             * 将pinpoint-bootstrap-1.7.3.jar文件、lib目录、boot目录下的jar包交给类加载器，并且指明启动类的类名为DefaultAgent
             */
            // this is the library list that must be loaded
            List<URL> libUrlList = resolveLib(classPathResolver);
            AgentClassLoader agentClassLoader = new AgentClassLoader(libUrlList.toArray(new URL[libUrlList.size()]));
            final String bootClass = getBootClass();
            agentClassLoader.setBootClass(bootClass); // 设置启动类的类名，生产环境下为DefaultAgent
            logger.info("pinpoint agent [" + bootClass + "] starting...");


            /**
             * 启动DefaultAgent
             */
            // 封装启动相关的参数对象
            AgentOption option = createAgentOption(agentId, applicationName, profilerConfig, instrumentation, pluginJars, bootstrapJarFile, serviceTypeRegistryService, annotationKeyRegistryService);
            // 通过类加载器引导类加载并实例化对象
            Agent pinpointAgent = agentClassLoader.boot(option);
            // 调用start方法将Agent启动工作交给DefaultAgent完成
            pinpointAgent.start();
            // 注册关闭hook，在当前上下文关闭的时候通知DefaultAgent处理相关资源
            registerShutdownHook(pinpointAgent);
            logger.info("pinpoint agent started normally.");
        } catch (Exception e) {
            // unexpected exception that did not be checked above
            logger.warn(ProductInfo.NAME + " start failed.", e);
            return false;
        }
        return true;
    }

    private String getBootClass() {
        final String agentType = getAgentType().toUpperCase();
        if (PLUGIN_TEST_AGENT.equals(agentType)) {
            return PLUGIN_TEST_BOOT_CLASS;
        }
        return BOOT_CLASS;
    }

    private String getAgentType() {
        String agentType = agentArgs.get(AGENT_TYPE);
        if (agentType == null) {
            return DEFAULT_AGENT;
        }
        return agentType;

    }

    private AgentOption createAgentOption(String agentId, String applicationName, ProfilerConfig profilerConfig,
                                          Instrumentation instrumentation,
                                          URL[] pluginJars,
                                          BootstrapJarFile bootstrapJarFile,
                                          ServiceTypeRegistryService serviceTypeRegistryService,
                                          AnnotationKeyRegistryService annotationKeyRegistryService) {
        List<String> bootstrapJarPaths = bootstrapJarFile.getJarNameList();
        return new DefaultAgentOption(instrumentation, agentId, applicationName, profilerConfig, pluginJars, bootstrapJarPaths, serviceTypeRegistryService, annotationKeyRegistryService);
    }

    // for test
    void setSystemProperty(SimpleProperty systemProperty) {
        this.systemProperty = systemProperty;
    }

    private void registerShutdownHook(final Agent pinpointAgent) {
        final Runnable stop = new Runnable() {
            @Override
            public void run() {
                pinpointAgent.stop();
            }
        };
        PinpointThreadFactory pinpointThreadFactory = new PinpointThreadFactory("Pinpoint-shutdown-hook", false);
        Thread thread = pinpointThreadFactory.newThread(stop);
        Runtime.getRuntime().addShutdownHook(thread);
    }


    private void saveLogFilePath(ClassPathResolver classPathResolver) {
        String agentLogFilePath = classPathResolver.getAgentLogFilePath();
        logger.info("logPath:" + agentLogFilePath);

        systemProperty.setProperty(ProductInfo.NAME + ".log", agentLogFilePath);
    }

    private void savePinpointVersion() {
        logger.info("pinpoint version:" + Version.VERSION);
        systemProperty.setProperty(ProductInfo.NAME + ".version", Version.VERSION);
    }

    private String getConfigPath(ClassPathResolver classPathResolver) {
        // 如果系统参数有配置pinpoint.config文件地址则从该地址获取
        final String configName = ProductInfo.NAME + ".config";
        String pinpointConfigFormSystemProperty = systemProperty.getProperty(configName);
        if (pinpointConfigFormSystemProperty != null) {
            logger.info(configName + " systemProperty found. " + pinpointConfigFormSystemProperty);
            return pinpointConfigFormSystemProperty;
        }

        // 如果系统中没有配置则从当前jar包所在目录下找pinpoint.config文件
        String classPathAgentConfigPath = classPathResolver.getAgentConfigPath();
        if (classPathAgentConfigPath != null) {
            logger.info("classpath " + configName + " found. " + classPathAgentConfigPath);
            return classPathAgentConfigPath;
        }

        logger.info(configName + " file not found.");
        return null;
    }


    private List<URL> resolveLib(ClassPathResolver classPathResolver) {
        // this method may handle only absolute path,  need to handle relative path (./..agentlib/lib)
        String agentJarFullPath = classPathResolver.getAgentJarFullPath();
        String agentLibPath = classPathResolver.getAgentLibPath();
        List<URL> urlList = resolveLib(classPathResolver.resolveLib()); // 获取boot目录、lib目录下所有jar、xml、properties文件地址
        String agentConfigPath = classPathResolver.getAgentConfigPath();

        if (logger.isInfoEnabled()) {
            logger.info("agent JarPath:" + agentJarFullPath);
            logger.info("agent LibDir:" + agentLibPath);
            for (URL url : urlList) {
                logger.info("agent Lib:" + url);
            }
            logger.info("agent config:" + agentConfigPath);
        }

        return urlList;
    }

    private List<URL> resolveLib(List<URL> urlList) {
        if (DEFAULT_AGENT.equals(getAgentType().toUpperCase())) {
            final List<URL> releaseLib = new ArrayList<URL>(urlList.size());
            for (URL url : urlList) {
                //
                if (!url.toExternalForm().contains("pinpoint-profiler-test")) {
                    releaseLib.add(url);
                }
            }
            return releaseLib;
        } else {
            logger.info("load " + PLUGIN_TEST_AGENT + " lib");
            // plugin test
            return urlList;
        }
    }

}
