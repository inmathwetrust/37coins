package com._37coins;

import javax.inject.Inject;

import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.jvnet.hk2.guice.bridge.api.GuiceBridge;
import org.jvnet.hk2.guice.bridge.api.GuiceIntoHK2Bridge;

import com.google.inject.Injector;

public class TestApplication extends ResourceConfig {
	
	public static Injector injector;

    @Inject
    public TestApplication(ServiceLocator serviceLocator) {
        // Set package to look for resources in
        packages("com._37coins.resources","org.glassfish.jersey.examples.jackson");

        System.out.println("Registering injectables...");

        GuiceBridge.getGuiceBridge().initializeGuiceBridge(serviceLocator);

        GuiceIntoHK2Bridge guiceBridge = serviceLocator.getService(GuiceIntoHK2Bridge.class);
        guiceBridge.bridgeGuiceInjector(TestServletConfig.injector);
        this.register(JacksonFeature.class);
    }
}