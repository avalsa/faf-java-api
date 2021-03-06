package com.faforever.api.user;

import com.faforever.api.config.FafApiProperties;
import com.faforever.api.data.domain.User;
import com.faforever.api.error.ApiException;
import com.faforever.api.error.Error;
import com.faforever.api.error.ErrorCode;
import com.faforever.api.security.OAuthScope;
import com.faforever.api.user.UserService.SteamLinkResult;
import com.faforever.api.utils.RemoteAddressUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@RestController
@RequestMapping(path = "/users")
@RequiredArgsConstructor
public class UsersController {
  private final FafApiProperties fafApiProperties;
  private final UserService userService;
  private final SteamService steamService;
  private final ObjectMapper objectMapper;

  @ApiOperation("Registers a new account that needs to be activated.")
  @RequestMapping(path = "/register", method = RequestMethod.POST, produces = APPLICATION_JSON_VALUE)
  @PreAuthorize("#oauth2.hasScope('" + OAuthScope._CREATE_USER + "')")
  public void register(HttpServletRequest request,
                       @RequestParam("username") String username,
                       @RequestParam("email") String email) {
    if (request.isUserInRole("USER")) {
      throw new ApiException(new Error(ErrorCode.ALREADY_REGISTERED));
    }

    userService.register(username, email);
  }

  @ApiOperation("Activates a previously registered account.")
  @RequestMapping(path = "/activate", method = RequestMethod.POST, produces = APPLICATION_JSON_VALUE)
  public void activate(HttpServletRequest request, HttpServletResponse response,
                       @RequestParam("token") String registrationToken,
                       @RequestParam("password") String password) throws IOException {
    userService.activate(registrationToken, password, RemoteAddressUtil.getRemoteAddress(request));
  }

  @PreAuthorize("#oauth2.hasScope('" + OAuthScope._WRITE_ACCOUNT_DATA + "') and hasRole('ROLE_USER')")
  @ApiOperation("Changes the password of a previously registered account.")
  @RequestMapping(path = "/changePassword", method = RequestMethod.POST, produces = APPLICATION_JSON_VALUE)
  public void changePassword(@RequestParam("currentPassword") String currentPassword, @RequestParam("newPassword") String newPassword, Authentication authentication) {
    userService.changePassword(currentPassword, newPassword, userService.getUser(authentication));
  }

  @PreAuthorize("#oauth2.hasScope('" + OAuthScope._WRITE_ACCOUNT_DATA + "') and hasRole('ROLE_USER')")
  @ApiOperation("Changes the login of a previously registered account.")
  @RequestMapping(path = "/changeUsername", method = RequestMethod.POST, produces = APPLICATION_JSON_VALUE)
  public void changeLogin(HttpServletRequest request, @RequestParam("newUsername") String newUsername, Authentication authentication) {
    userService.changeLogin(newUsername, userService.getUser(authentication), RemoteAddressUtil.getRemoteAddress(request));
  }

  @PreAuthorize("#oauth2.hasScope('" + OAuthScope._WRITE_ACCOUNT_DATA + "') and hasAnyRole('ROLE_MODERATOR', 'ROLE_ADMINISTRATOR')")
  @ApiOperation("Force the change of the login of a user with the given userId.")
  @RequestMapping(path = "/{userId}/forceChangeUsername", method = RequestMethod.POST, produces = APPLICATION_JSON_VALUE)
  public void forceChangeLogin(HttpServletRequest request, @RequestParam("newUsername") String newUsername, @PathVariable("userId") String userId) {
    User user = userService.getUser(Integer.parseInt(userId));
    userService.changeLoginForced(newUsername, user, RemoteAddressUtil.getRemoteAddress(request));
  }

  @PreAuthorize("#oauth2.hasScope('" + OAuthScope._WRITE_ACCOUNT_DATA + "') and hasRole('ROLE_USER')")
  @ApiOperation("Changes the email of a previously registered account.")
  @RequestMapping(path = "/changeEmail", method = RequestMethod.POST, produces = APPLICATION_JSON_VALUE)
  public void changeEmail(HttpServletRequest request, @RequestParam("currentPassword") String currentPassword, @RequestParam("newEmail") String newEmail, Authentication authentication) {
    userService.changeEmail(currentPassword, newEmail, userService.getUser(authentication), RemoteAddressUtil.getRemoteAddress(request));
  }


  @ApiOperation("Sends a password reset request to the username OR email linked by this account.")
  @RequestMapping(path = "/requestPasswordReset", method = RequestMethod.POST, produces = APPLICATION_JSON_VALUE)
  public void requestPasswordReset(@RequestParam("identifier") String identifier) {
    userService.requestPasswordReset(identifier);
  }

  @ApiOperation("Sets a new password for an account.")
  @RequestMapping(path = "/performPasswordReset", method = RequestMethod.POST, produces = APPLICATION_JSON_VALUE)
  public void performPasswordReset(HttpServletResponse response,
                                   @RequestParam("token") String token,
                                   @RequestParam("newPassword") String newPassword) throws IOException {
    userService.performPasswordReset(token, newPassword);
  }

  @PreAuthorize("#oauth2.hasScope('" + OAuthScope._WRITE_ACCOUNT_DATA + "') and hasRole('ROLE_USER')")
  @ApiOperation("Creates an URL to the steam platform to initiate the Link To Steam process.")
  @RequestMapping(path = "/buildSteamLinkUrl", method = RequestMethod.POST, produces = APPLICATION_JSON_VALUE)
  public Map<String, Serializable> buildSteamLinkUrl(Authentication authentication, @RequestParam("callbackUrl") String callbackUrl) {
    String steamUrl = userService.buildSteamLinkUrl(userService.getUser(authentication), callbackUrl);
    return ImmutableMap.of("steamUrl", steamUrl);
  }

  @ApiOperation("Processes the Steam redirect and creates the steam link in the user account.")
  @RequestMapping(path = "/linkToSteam", method = RequestMethod.GET, produces = APPLICATION_JSON_VALUE)
  public void linkToSteam(HttpServletRequest request,
                          HttpServletResponse response,
                          @RequestParam("token") String token) throws IOException {
    SteamLinkResult result = userService.linkToSteam(token, steamService.parseSteamIdFromLoginRedirect(request));
    if (!result.getErrors().isEmpty()) {
      UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(result.getCallbackUrl());
      String errorsJson = objectMapper.writeValueAsString(result.getErrors());
      uriBuilder.queryParam("errors", errorsJson);
      response.sendRedirect(uriBuilder.toUriString());
      return;
    }

    response.sendRedirect(result.getCallbackUrl());
  }
}
