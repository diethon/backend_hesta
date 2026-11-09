package com.hesta.backend.security;

import com.hesta.backend.security.policy.AccessPolicyType;
import com.hesta.backend.security.policy.EndpointPolicyRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Method;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deny-by-default policy scanner test (Correction C11).
 * Inspects all Spring MVC @RestController and @Controller classes in com.hesta.backend.controller.
 * Ensures that EVERY endpoint method either has an explicit @HomeAccessPolicy annotation,
 * a class-level policy, or an entry in EndpointPolicyRegistry / public allowlist.
 * If any new endpoint is introduced without declaring its policy, this test will FAIL.
 */
class AuthorizationPolicyScannerTest {

    @Test
    @DisplayName("Verify every controller endpoint method declares an explicit authorization policy (Deny-By-Default)")
    void allControllerEndpointsMustDeclareAuthorizationPolicy() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        Set<BeanDefinition> candidateBeans = scanner.findCandidateComponents("com.hesta.backend.controller");
        assertThat(candidateBeans).isNotEmpty();

        List<String> undeclaredEndpoints = new ArrayList<>();
        int totalEndpointsScanned = 0;

        for (BeanDefinition beanDef : candidateBeans) {
            Class<?> clazz = Class.forName(beanDef.getBeanClassName());

            for (Method method : clazz.getDeclaredMethods()) {
                if (isEndpointMethod(method)) {
                    totalEndpointsScanned++;
                    Optional<AccessPolicyType> policyOpt = EndpointPolicyRegistry.resolvePolicy(clazz, method);

                    if (policyOpt.isEmpty()) {
                        undeclaredEndpoints.add(String.format(
                                "%s.%s() has no declared authorization policy",
                                clazz.getSimpleName(),
                                method.getName()
                        ));
                    }
                }
            }
        }

        System.out.printf("Authorization Scanner: verified %d endpoints across %d controllers. Undeclared: %d%n",
                totalEndpointsScanned, candidateBeans.size(), undeclaredEndpoints.size());

        assertThat(undeclaredEndpoints)
                .withFailMessage("The following endpoints violate Deny-by-default (missing @HomeAccessPolicy or registry declaration):\n"
                        + String.join("\n", undeclaredEndpoints))
                .isEmpty();
    }

    private boolean isEndpointMethod(Method method) {
        return method.isAnnotationPresent(GetMapping.class)
                || method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class)
                || method.isAnnotationPresent(PatchMapping.class)
                || method.isAnnotationPresent(RequestMapping.class);
    }
}
