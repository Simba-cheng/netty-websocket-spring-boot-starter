package org.yeauty.standard;

import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.beans.factory.support.AbstractBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.support.ApplicationObjectSupport;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.ClassUtils;
import org.yeauty.annotation.EnableWebSocket;
import org.yeauty.annotation.ServerEndpoint;
import org.yeauty.exception.DeploymentException;
import org.yeauty.pojo.PojoEndpointServer;
import org.yeauty.pojo.PojoMethodMapping;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.util.*;

/**
 * @author Yeauty
 */
public class ServerEndpointExporter extends ApplicationObjectSupport implements SmartInitializingSingleton, BeanFactoryAware, ResourceLoaderAware {

    @Autowired
    Environment environment;

    private AbstractBeanFactory beanFactory;

    private ResourceLoader resourceLoader;

    private final Map<InetSocketAddress, WebsocketServer> addressWebsocketServerMap = new HashMap<>();

    @Override
    public void afterSingletonsInstantiated() {
        registerEndpoints();
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        if (!(beanFactory instanceof AbstractBeanFactory)) {
            throw new IllegalArgumentException(
                    "AutowiredAnnotationBeanPostProcessor requires a AbstractBeanFactory: " + beanFactory);
        }
        this.beanFactory = (AbstractBeanFactory) beanFactory;
    }

    protected void registerEndpoints() {
        // 返回与此对象关联的 ApplicationContext。
        ApplicationContext context = getApplicationContext();

        scanPackage(context);

        // 从 ApplicationContext 中获取所有使用了 ServerEndpoint注解 的类。
        String[] endpointBeanNames = context.getBeanNamesForAnnotation(ServerEndpoint.class);
        Set<Class<?>> endpointClasses = new LinkedHashSet<>();
        for (String beanName : endpointBeanNames) {
            endpointClasses.add(context.getType(beanName));
        }

        // 注册
        for (Class<?> endpointClass : endpointClasses) {
            // 检查指定的类是否是CGLIB生成的类(当ServerEndpoint类被cglib代理时(如aop增强)，仍能正常运行).
            if (ClassUtils.isCglibProxyClass(endpointClass)) {
                registerEndpoint(endpointClass.getSuperclass());
            } else {
                registerEndpoint(endpointClass);
            }
        }

        init();
    }

    private void scanPackage(ApplicationContext context) {
        String[] basePackages = null;

        /*
            EnableWebSocket注解中的scanBasePackages属性，用于指定待扫描的包路径。
         */
        String[] enableWebSocketBeanNames = context.getBeanNamesForAnnotation(EnableWebSocket.class);
        if (enableWebSocketBeanNames.length != 0) {
            for (String enableWebSocketBeanName : enableWebSocketBeanNames) {
                // 从 ApplicationContext 中获取bean对象,根据beanName
                Object enableWebSocketBean = context.getBean(enableWebSocketBeanName);
                // 获取bean中 EnableWebSocket注解上的配置
                EnableWebSocket enableWebSocket = AnnotationUtils.findAnnotation(enableWebSocketBean.getClass(), EnableWebSocket.class);
                assert enableWebSocket != null;
                if (enableWebSocket.scanBasePackages().length != 0) {
                    basePackages = enableWebSocket.scanBasePackages();
                    break;
                }
            }
        }

        /*
            如果没有配置 EnableWebSocket注解，或者 EnableWebSocket注解 上没有使用 scanBasePackages属性，
            则读取 SpringBootApplication注解 上的 scanBasePackages属性，用作待扫描的包路径。

            如果 SpringBootApplication注解 上的 scanBasePackages属性 没有使用，则将主类所在的包设为待扫描的包路径。
         */
        // use @SpringBootApplication package
        if (basePackages == null) {
            // springBoot主类所在包路径
            String[] springBootApplicationBeanName = context.getBeanNamesForAnnotation(SpringBootApplication.class);
            Object springBootApplicationBean = context.getBean(springBootApplicationBeanName[0]);
            SpringBootApplication springBootApplication = AnnotationUtils.findAnnotation(springBootApplicationBean.getClass(), SpringBootApplication.class);
            assert springBootApplication != null;
            if (springBootApplication.scanBasePackages().length != 0) {
                basePackages = springBootApplication.scanBasePackages();
            } else {
                // 获取 springBoot主类 所在的包路径
                String packageName = ClassUtils.getPackageName(springBootApplicationBean.getClass().getName());
                basePackages = new String[1];
                basePackages[0] = packageName;
            }
        }

        // 从指定包路径扫描 ServerEndpoint注解，具体做什么用待研究
        EndpointClassPathScanner scanHandle = new EndpointClassPathScanner((BeanDefinitionRegistry) context, false);
        if (resourceLoader != null) {
            scanHandle.setResourceLoader(resourceLoader);
        }

        for (String basePackage : basePackages) {
            scanHandle.doScan(basePackage);
        }
    }

    private void init() {
        for (Map.Entry<InetSocketAddress, WebsocketServer> entry : addressWebsocketServerMap.entrySet()) {
            WebsocketServer websocketServer = entry.getValue();
            try {
                websocketServer.init();
                PojoEndpointServer pojoEndpointServer = websocketServer.getPojoEndpointServer();
                StringJoiner stringJoiner = new StringJoiner(",");
                pojoEndpointServer.getPathMatcherSet().forEach(pathMatcher -> stringJoiner.add("'" + pathMatcher.getPattern() + "'"));
                logger.info(String.format("\033[34mNetty WebSocket started on port: %s with context path(s): %s .\033[0m", pojoEndpointServer.getPort(), stringJoiner.toString()));
            } catch (InterruptedException e) {
                logger.error(String.format("websocket [%s] init fail", entry.getKey()), e);
            } catch (SSLException e) {
                logger.error(String.format("websocket [%s] ssl create fail", entry.getKey()), e);

            }
        }
    }

    private void registerEndpoint(Class<?> endpointClass) {
        // 获取类上ServerEndpoint注解的配置
        ServerEndpoint annotation = AnnotatedElementUtils.findMergedAnnotation(endpointClass, ServerEndpoint.class);
        if (annotation == null) {
            throw new IllegalStateException("missingAnnotation ServerEndpoint");
        }
        // 根据 ServerEndpoint主街上配置的数据构建实例对象
        ServerEndpointConfig serverEndpointConfig = buildConfig(annotation);

        ApplicationContext context = getApplicationContext();
        PojoMethodMapping pojoMethodMapping = null;
        try {
            pojoMethodMapping = new PojoMethodMapping(endpointClass, context, beanFactory);
        } catch (DeploymentException e) {
            throw new IllegalStateException("Failed to register ServerEndpointConfig: " + serverEndpointConfig, e);
        }

        InetSocketAddress inetSocketAddress = new InetSocketAddress(serverEndpointConfig.getHost(), serverEndpointConfig.getPort());
        String path = resolveAnnotationValue(annotation.value(), String.class, "path");

        WebsocketServer websocketServer = addressWebsocketServerMap.get(inetSocketAddress);
        if (websocketServer == null) {
            PojoEndpointServer pojoEndpointServer = new PojoEndpointServer(pojoMethodMapping, serverEndpointConfig, path);
            websocketServer = new WebsocketServer(pojoEndpointServer, serverEndpointConfig);
            addressWebsocketServerMap.put(inetSocketAddress, websocketServer);
        } else {
            websocketServer.getPojoEndpointServer().addPathPojoMethodMapping(path, pojoMethodMapping);
        }
    }

    private ServerEndpointConfig buildConfig(ServerEndpoint annotation) {
        String host = resolveAnnotationValue(annotation.host(), String.class, "host");
        int port = resolveAnnotationValue(annotation.port(), Integer.class, "port");
        String path = resolveAnnotationValue(annotation.value(), String.class, "value");
        int bossLoopGroupThreads = resolveAnnotationValue(annotation.bossLoopGroupThreads(), Integer.class, "bossLoopGroupThreads");
        int workerLoopGroupThreads = resolveAnnotationValue(annotation.workerLoopGroupThreads(), Integer.class, "workerLoopGroupThreads");
        boolean useCompressionHandler = resolveAnnotationValue(annotation.useCompressionHandler(), Boolean.class, "useCompressionHandler");

        int optionConnectTimeoutMillis = resolveAnnotationValue(annotation.optionConnectTimeoutMillis(), Integer.class, "optionConnectTimeoutMillis");
        int optionSoBacklog = resolveAnnotationValue(annotation.optionSoBacklog(), Integer.class, "optionSoBacklog");

        int childOptionWriteSpinCount = resolveAnnotationValue(annotation.childOptionWriteSpinCount(), Integer.class, "childOptionWriteSpinCount");
        int childOptionWriteBufferHighWaterMark = resolveAnnotationValue(annotation.childOptionWriteBufferHighWaterMark(), Integer.class, "childOptionWriteBufferHighWaterMark");
        int childOptionWriteBufferLowWaterMark = resolveAnnotationValue(annotation.childOptionWriteBufferLowWaterMark(), Integer.class, "childOptionWriteBufferLowWaterMark");
        int childOptionSoRcvbuf = resolveAnnotationValue(annotation.childOptionSoRcvbuf(), Integer.class, "childOptionSoRcvbuf");
        int childOptionSoSndbuf = resolveAnnotationValue(annotation.childOptionSoSndbuf(), Integer.class, "childOptionSoSndbuf");
        boolean childOptionTcpNodelay = resolveAnnotationValue(annotation.childOptionTcpNodelay(), Boolean.class, "childOptionTcpNodelay");
        boolean childOptionSoKeepalive = resolveAnnotationValue(annotation.childOptionSoKeepalive(), Boolean.class, "childOptionSoKeepalive");
        int childOptionSoLinger = resolveAnnotationValue(annotation.childOptionSoLinger(), Integer.class, "childOptionSoLinger");
        boolean childOptionAllowHalfClosure = resolveAnnotationValue(annotation.childOptionAllowHalfClosure(), Boolean.class, "childOptionAllowHalfClosure");

        int readerIdleTimeSeconds = resolveAnnotationValue(annotation.readerIdleTimeSeconds(), Integer.class, "readerIdleTimeSeconds");
        int writerIdleTimeSeconds = resolveAnnotationValue(annotation.writerIdleTimeSeconds(), Integer.class, "writerIdleTimeSeconds");
        int allIdleTimeSeconds = resolveAnnotationValue(annotation.allIdleTimeSeconds(), Integer.class, "allIdleTimeSeconds");

        int maxFramePayloadLength = resolveAnnotationValue(annotation.maxFramePayloadLength(), Integer.class, "maxFramePayloadLength");

        boolean useEventExecutorGroup = resolveAnnotationValue(annotation.useEventExecutorGroup(), Boolean.class, "useEventExecutorGroup");
        int eventExecutorGroupThreads = resolveAnnotationValue(annotation.eventExecutorGroupThreads(), Integer.class, "eventExecutorGroupThreads");

        String sslKeyPassword = resolveAnnotationValue(annotation.sslKeyPassword(), String.class, "sslKeyPassword");
        String sslKeyStore = resolveAnnotationValue(annotation.sslKeyStore(), String.class, "sslKeyStore");
        String sslKeyStorePassword = resolveAnnotationValue(annotation.sslKeyStorePassword(), String.class, "sslKeyStorePassword");
        String sslKeyStoreType = resolveAnnotationValue(annotation.sslKeyStoreType(), String.class, "sslKeyStoreType");
        String sslTrustStore = resolveAnnotationValue(annotation.sslTrustStore(), String.class, "sslTrustStore");
        String sslTrustStorePassword = resolveAnnotationValue(annotation.sslTrustStorePassword(), String.class, "sslTrustStorePassword");
        String sslTrustStoreType = resolveAnnotationValue(annotation.sslTrustStoreType(), String.class, "sslTrustStoreType");

        String[] corsOrigins = annotation.corsOrigins();
        if (corsOrigins.length != 0) {
            for (int i = 0; i < corsOrigins.length; i++) {
                corsOrigins[i] = resolveAnnotationValue(corsOrigins[i], String.class, "corsOrigins");
            }
        }
        Boolean corsAllowCredentials = resolveAnnotationValue(annotation.corsAllowCredentials(), Boolean.class, "corsAllowCredentials");

        ServerEndpointConfig serverEndpointConfig = new ServerEndpointConfig(host, port, bossLoopGroupThreads, workerLoopGroupThreads
                , useCompressionHandler, optionConnectTimeoutMillis, optionSoBacklog, childOptionWriteSpinCount, childOptionWriteBufferHighWaterMark
                , childOptionWriteBufferLowWaterMark, childOptionSoRcvbuf, childOptionSoSndbuf, childOptionTcpNodelay, childOptionSoKeepalive
                , childOptionSoLinger, childOptionAllowHalfClosure, readerIdleTimeSeconds, writerIdleTimeSeconds, allIdleTimeSeconds
                , maxFramePayloadLength, useEventExecutorGroup, eventExecutorGroupThreads
                , sslKeyPassword, sslKeyStore, sslKeyStorePassword, sslKeyStoreType
                , sslTrustStore, sslTrustStorePassword, sslTrustStoreType
                , corsOrigins, corsAllowCredentials);

        return serverEndpointConfig;
    }

    private <T> T resolveAnnotationValue(Object value, Class<T> requiredType, String paramName) {
        if (value == null) {
            return null;
        }
        TypeConverter typeConverter = beanFactory.getTypeConverter();

        if (value instanceof String) {
            String strVal = beanFactory.resolveEmbeddedValue((String) value);
            BeanExpressionResolver beanExpressionResolver = beanFactory.getBeanExpressionResolver();
            if (beanExpressionResolver != null) {
                value = beanExpressionResolver.evaluate(strVal, new BeanExpressionContext(beanFactory, null));
            } else {
                value = strVal;
            }
        }
        try {
            return typeConverter.convertIfNecessary(value, requiredType);
        } catch (TypeMismatchException e) {
            throw new IllegalArgumentException("Failed to convert value of parameter '" + paramName + "' to required type '" + requiredType.getName() + "'");
        }
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }
}
