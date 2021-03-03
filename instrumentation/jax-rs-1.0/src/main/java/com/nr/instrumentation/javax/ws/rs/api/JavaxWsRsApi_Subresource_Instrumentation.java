/*
 *
 *  * Copyright 2020 New Relic Corporation. All rights reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.nr.instrumentation.javax.ws.rs.api;

import com.newrelic.agent.bridge.AgentBridge;
import com.newrelic.agent.bridge.Transaction;
import com.newrelic.agent.bridge.TransactionNamePriority;
import com.newrelic.api.agent.Trace;
import com.newrelic.api.agent.weaver.WeaveIntoAllMethods;
import com.newrelic.api.agent.weaver.WeavePriorityOrder;
import com.newrelic.api.agent.weaver.WeaveWithAnnotation;
import com.newrelic.api.agent.weaver.Weaver;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import java.util.Queue;
import java.util.logging.Level;

/**
 * This instrumentation class is different from {@link JavaxWsRsApi_Instrumentation} because it does not look at class
 * annotations for matching purposes.
 *
 * We capture the @Path method annotation if it's there but don't match on it because there can't be just an @Path
 * annotation on a method if it's not a subresource.
 *
 * Case 1: Class annotation = @Path, method annotation = @Path, @GET
 * JavaxWsRsApi_Instrumentation will grab both path annotations and add them to the transaction name.
 *
 * Case 2: Class annotation = @Path, method annotation = @GET, subresource method annotation = @Path
 * JavaxWsRsApi_Instrumentation annotation will grab the class path and JavaxWsRsApi_SubResource_Instrumentation will grab the subresource path.
 *
 * Case 3: Class annotation = @Path, method annotation = @Path
 * This can't happen.
 */
@WeavePriorityOrder(0) // Higher priority than JavaxWsRsApi_Instrumentation in WeavePackage.processWeaveBytes
public class JavaxWsRsApi_Subresource_Instrumentation {

    @WeaveWithAnnotation(annotationClasses = { "javax.ws.rs.PUT", "javax.ws.rs.POST", "javax.ws.rs.GET",
            "javax.ws.rs.DELETE", "javax.ws.rs.HEAD", "javax.ws.rs.OPTIONS", "javax.ws.rs.Path", "javax.ws.rs.PATCH" })
    @WeaveIntoAllMethods
    @Trace(dispatcher = true)
    private static void instrumentation() {
        Transaction transaction = AgentBridge.getAgent().getWeakRefTransaction(false);
        if (transaction != null) {
            JavaxWsRsApiHelper.ResourcePath resourcePath = JavaxWsRsApiHelper.subresourcePath.get();
            if (!transaction.equals(resourcePath.transaction)) {
                resourcePath.pathQueue.clear();
                resourcePath.transaction = transaction;
            }

            String rootPath = null;
            Path rootPathAnnotation = Weaver.getClassAnnotation(Path.class);
            if (rootPathAnnotation != null) {
                rootPath = rootPathAnnotation.value();
            }
            String methodPath = null;
            Path methodPathAnnotation = Weaver.getMethodAnnotation(Path.class);
            if (methodPathAnnotation != null) {
                methodPath = methodPathAnnotation.value();
            }

            String httpMethod = null;
             /* Unfortunately we have to do this in a big if/else block because we can't currently support variables passed
             into the Weaver.getMethodAnnotation method. We might be able to make an improvement to handle this in the future */
            if (Weaver.getMethodAnnotation(PUT.class) != null) {
                httpMethod = PUT.class.getSimpleName();
            } else if (Weaver.getMethodAnnotation(POST.class) != null) {
                httpMethod = POST.class.getSimpleName();
            } else if (Weaver.getMethodAnnotation(GET.class) != null) {
                httpMethod = GET.class.getSimpleName();
            } else if (Weaver.getMethodAnnotation(DELETE.class) != null) {
                httpMethod = DELETE.class.getSimpleName();
            } else if (Weaver.getMethodAnnotation(HEAD.class) != null) {
                httpMethod = HEAD.class.getSimpleName();
            } else if (Weaver.getMethodAnnotation(OPTIONS.class) != null) {
                httpMethod = OPTIONS.class.getSimpleName();
            } else {
                // PATCH annotation was added later so may not be available
                try {
                    if (Weaver.getMethodAnnotation(PATCH.class) != null) {
                        httpMethod = PATCH.class.getSimpleName();
                    }
                } catch (NoClassDefFoundError e) {
                }
            }

            if (httpMethod != null) {
                Queue<String> subresourcePath = resourcePath.pathQueue;

                String fullPath = JavaxWsRsApiHelper.getPath(rootPath, methodPath, httpMethod);
                subresourcePath.offer(fullPath);

                StringBuilder txNameBuilder = new StringBuilder();
                String pathPart;
                while ((pathPart = subresourcePath.poll()) != null) {
                    txNameBuilder.append(pathPart);
                }

                transaction.setTransactionName(TransactionNamePriority.FRAMEWORK_HIGH, false, "RestWebService",
                        txNameBuilder.toString());
            } else {
                String fullPath = JavaxWsRsApiHelper.getPath(rootPath, methodPath, null);
                if (!resourcePath.pathQueue.offer(fullPath)) {
                    AgentBridge.getAgent().getLogger().log(Level.FINE, "JAX-RS Subresource naming queue is full.");
                    resourcePath.pathQueue.clear();
                }
            }
        }
    }
}
