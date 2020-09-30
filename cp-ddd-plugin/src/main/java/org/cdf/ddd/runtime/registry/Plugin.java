/*
 * Copyright cp-ddd-framework Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.cdf.ddd.runtime.registry;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.cdf.ddd.annotation.Extension;
import org.cdf.ddd.annotation.Partner;
import org.cdf.ddd.plugin.IContainerContext;
import org.cdf.ddd.plugin.IPluginListener;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.io.File;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A Plugin is a dynamic loadable Jar that has a dedicated class loader.
 * <p>
 * <p>Plugin Jar是可以被动态加载的Jar = (Pattern + Extension) | (Partner + Extension)</p>
 */
@Slf4j
class Plugin implements IPlugin {
    private static final String pluginXml = "/plugin.xml";

    @Getter
    private final String code;

    private final ClassLoader jdkClassLoader;
    private final ClassLoader containerClassLoader;
    private ClassLoader pluginClassLoader;

    private ApplicationContext applicationContext;
    private IPluginListener pluginListener;

    Plugin(String code, ClassLoader jdkClassLoader, ClassLoader containerClassLoader) {
        this.code = code;
        this.jdkClassLoader = jdkClassLoader;
        this.containerClassLoader = containerClassLoader;
    }

    Plugin load(String jarPath, boolean useSpring, Class<? extends Annotation> identityResolverClass, IContainerContext ctx) throws Throwable {
        Map<Class<? extends Annotation>, List<Class>> plugableMap = prepare(jarPath, useSpring, identityResolverClass);
        log.info("prepared {} with plugableMap {}", jarPath, plugableMap);

        if (pluginListener != null) {
            pluginListener.onPrepared(ctx);
        }

        // 现在，新jar里的类已经被新的ClassLoader加载到内存了，也实例化了，但旧jar里的类仍然在工作
        commit(identityResolverClass, plugableMap);
        log.info("committed {}", jarPath);

        if (pluginListener != null) {
            pluginListener.onSwitched(ctx);
        }

        return this;
    }

    // load all relevant classes with the new PluginClassLoader
    private Map<Class<? extends Annotation>, List<Class>> prepare(String jarPath, boolean useSpring, Class<? extends Annotation> identityResolverClass) throws Throwable {
        // each Plugin Jar has a specific PluginClassLoader
        pluginClassLoader = new PluginClassLoader(new URL[]{new File(jarPath).toURI().toURL()}, jdkClassLoader, containerClassLoader);

        if (useSpring) {
            log.info("Spring loading Plugin with {}, {}, {} ...", jdkClassLoader, containerClassLoader, pluginClassLoader);
            long t0 = System.nanoTime();

            // each Plugin Jar will have a specific Spring IoC with the same parent
            applicationContext = new ClassPathXmlApplicationContext(new String[]{pluginXml}, DDDBootstrap.applicationContext()) {
                protected void initBeanDefinitionReader(XmlBeanDefinitionReader reader) {
                    super.initBeanDefinitionReader(reader);
                    reader.setBeanClassLoader(pluginClassLoader);
                    setClassLoader(pluginClassLoader); // so that it can find the pluginXml
                }
            };

            log.info("Spring loading cost {}ms", (System.nanoTime() - t0) / 1000_000);
        }

        // 从Plugin Jar里把 IPlugable 挑出来，以便更新注册表
        List<Class<? extends Annotation>> annotations = new ArrayList<>(2);
        annotations.add(identityResolverClass);
        annotations.add(Extension.class);
        Map<Class<? extends Annotation>, List<Class>> plugableMap = JarUtils.loadClassWithAnnotations(
                jarPath, annotations, null, pluginClassLoader);

        // IPluginListener 不通过Spring加载
        this.pluginListener = JarUtils.loadBeanWithType(pluginClassLoader, jarPath, IPluginListener.class);

        return plugableMap;
    }

    // switch IdentityResolverClass with the new instances
    private void commit(Class<? extends Annotation> identityResolverClass, Map<Class<? extends Annotation>, List<Class>> plugableMap) {
        List<Class> identityResolverClasses = plugableMap.get(identityResolverClass);
        if (identityResolverClasses != null && !identityResolverClasses.isEmpty()) {
            if (identityResolverClass == Partner.class && identityResolverClasses.size() > 1) {
                throw new RuntimeException("One Partner jar can have at most 1 Partner instance!");
            }

            for (Class irc : identityResolverClasses) {
                log.info("Indexing {}", irc.getCanonicalName());
                // 每次加载，由于 PluginClassLoader 是不同的，irc也是不同的
                Object partnerOrPattern = applicationContext.getBean(irc);
                RegistryFactory.lazyRegister(identityResolverClass, partnerOrPattern);
            }
        }

        List<Class> extensions = plugableMap.get(Extension.class);
        if (extensions != null && !extensions.isEmpty()) {
            for (Class extensionClazz : extensions) {
                log.info("Indexing {}", extensionClazz.getCanonicalName());
                Object extension = applicationContext.getBean(extensionClazz);
                RegistryFactory.lazyRegister(Extension.class, extension);
            }
        }
    }
}
