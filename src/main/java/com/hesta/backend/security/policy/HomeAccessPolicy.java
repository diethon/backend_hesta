package com.hesta.backend.security.policy;

import java.lang.annotation.*;

/**
 * Declares the authorization policy for a controller class or handler method.
 * Enforced by AuthorizationPolicyScannerTest to maintain a deny-by-default posture.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface HomeAccessPolicy {
    AccessPolicyType value();
    String description() default "";
}
