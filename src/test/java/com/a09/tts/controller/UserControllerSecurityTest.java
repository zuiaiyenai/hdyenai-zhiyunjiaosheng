package com.a09.tts.controller;

import com.a09.tts.pojo.User;
import com.a09.tts.security.LoginRateLimiter;
import com.a09.tts.security.PasswordPolicy;
import com.a09.tts.service.UserService;
import com.a09.tts.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class UserControllerSecurityTest {
    @Test
    void unknownUserAndWrongPasswordReturnSameResponse() {
        UserService users = mock(UserService.class);
        LoginRateLimiter limiter = mock(LoginRateLimiter.class);
        UserController controller = controller(users, limiter);
        when(users.login(anyString(), anyString())).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        var unknown = controller.loginUser(new User("missing", "Password1", null), request);
        var wrong = controller.loginUser(new User("existing", "Password1", null), request);

        assertEquals(HttpStatus.UNAUTHORIZED, unknown.getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, wrong.getStatusCode());
        assertEquals("用户名或密码错误", unknown.getBody().get("msg"));
        assertEquals(unknown.getBody().get("msg"), wrong.getBody().get("msg"));
        verify(users, never()).isUsernameExist(anyString());
    }

    @Test
    void blockedLoginReturnsTooManyRequestsWithoutCheckingPassword() {
        UserService users = mock(UserService.class);
        LoginRateLimiter limiter = mock(LoginRateLimiter.class);
        when(limiter.isBlocked("127.0.0.1", "alice")).thenReturn(true);
        UserController controller = controller(users, limiter);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        var response = controller.loginUser(new User("alice", "Password1", null), request);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        verify(users, never()).login(anyString(), anyString());
    }

    @Test
    void registrationNeverAcceptsClientSuppliedPermission() {
        UserService users = mock(UserService.class);
        LoginRateLimiter limiter = mock(LoginRateLimiter.class);
        when(users.register(org.mockito.ArgumentMatchers.any(User.class))).thenReturn(1);
        UserController controller = controller(users, limiter);

        var response = controller.registerUser(new User("alice", "Password1", true));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        ArgumentCaptor<User> registered = ArgumentCaptor.forClass(User.class);
        verify(users).register(registered.capture());
        assertFalse(registered.getValue().getPermission());
    }

    private UserController controller(UserService users, LoginRateLimiter limiter) {
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "jwtUtil", mock(JwtUtil.class));
        ReflectionTestUtils.setField(controller, "passwordPolicy", mock(PasswordPolicy.class));
        ReflectionTestUtils.setField(controller, "loginRateLimiter", limiter);
        return controller;
    }
}
