package com.faforever.api.user;

import com.faforever.api.config.FafApiProperties;
import com.faforever.api.data.domain.GlobalRating;
import com.faforever.api.data.domain.Ladder1v1Rating;
import com.faforever.api.data.domain.NameRecord;
import com.faforever.api.data.domain.User;
import com.faforever.api.email.EmailService;
import com.faforever.api.error.ApiException;
import com.faforever.api.error.Error;
import com.faforever.api.error.ErrorCode;
import com.faforever.api.player.PlayerRepository;
import com.faforever.api.rating.GlobalRatingRepository;
import com.faforever.api.rating.Ladder1v1RatingRepository;
import com.faforever.api.security.FafPasswordEncoder;
import com.faforever.api.security.FafTokenService;
import com.faforever.api.security.FafTokenType;
import com.faforever.api.security.FafUserDetails;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.faforever.api.error.ErrorCode.TOKEN_INVALID;

@Service
@Slf4j
public class UserService {
  static final String KEY_USERNAME = "username";
  static final String KEY_EMAIL = "email";
  static final String KEY_USER_ID = "id";
  static final String KEY_STEAM_LINK_CALLBACK_URL = "callbackUrl";

  private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{2,15}$");
  private static final String USER_REGISTRATIONS_COUNT = "user.registrations.count";
  private static final String USER_PASSWORD_RESET_COUNT = "user.password.reset.count";
  private static final String STEP_TAG = "step";

  private final EmailService emailService;
  private final PlayerRepository playerRepository;
  private final UserRepository userRepository;
  private final NameRecordRepository nameRecordRepository;
  private final FafApiProperties properties;
  private final FafPasswordEncoder passwordEncoder;
  private final AnopeUserRepository anopeUserRepository;
  private final FafTokenService fafTokenService;
  private final SteamService steamService;
  private final GlobalRatingRepository globalRatingRepository;
  private final Ladder1v1RatingRepository ladder1v1RatingRepository;
  private final MeterRegistry meterRegistry;
  private final Counter userRegistrationCounter;
  private final Counter userActivationCounter;
  private final Counter userSteamLinkRequestedCounter;
  private final Counter userSteamLinkDoneCounter;
  private final Counter userNameChangeCounter;
  private final Counter userPasswordResetRequestCounter;
  private final Counter userPasswordResetDoneCounter;
  private final ApplicationEventPublisher eventPublisher;

  public UserService(EmailService emailService,
                     PlayerRepository playerRepository,
                     UserRepository userRepository,
                     NameRecordRepository nameRecordRepository,
                     FafApiProperties properties,
                     AnopeUserRepository anopeUserRepository,
                     FafTokenService fafTokenService,
                     SteamService steamService,
                     GlobalRatingRepository globalRatingRepository,
                     Ladder1v1RatingRepository ladder1v1RatingRepository,
                     MeterRegistry meterRegistry,
                     ApplicationEventPublisher eventPublisher) {
    this.emailService = emailService;
    this.playerRepository = playerRepository;
    this.userRepository = userRepository;
    this.nameRecordRepository = nameRecordRepository;
    this.properties = properties;
    this.anopeUserRepository = anopeUserRepository;
    this.fafTokenService = fafTokenService;
    this.steamService = steamService;
    this.globalRatingRepository = globalRatingRepository;
    this.ladder1v1RatingRepository = ladder1v1RatingRepository;
    this.eventPublisher = eventPublisher;
    this.meterRegistry = meterRegistry;

    this.passwordEncoder = new FafPasswordEncoder();

    userRegistrationCounter = meterRegistry.counter(USER_REGISTRATIONS_COUNT, STEP_TAG, "registration");
    userActivationCounter = meterRegistry.counter(USER_REGISTRATIONS_COUNT, STEP_TAG, "activation");
    userSteamLinkRequestedCounter = meterRegistry.counter(USER_REGISTRATIONS_COUNT, STEP_TAG, "steamLinkRequested");
    userSteamLinkDoneCounter = meterRegistry.counter(USER_REGISTRATIONS_COUNT, STEP_TAG, "steamLinkDone");
    userNameChangeCounter = meterRegistry.counter("user.name.change.count");
    userPasswordResetRequestCounter = meterRegistry.counter(USER_PASSWORD_RESET_COUNT, STEP_TAG, "request");
    userPasswordResetDoneCounter = meterRegistry.counter(USER_PASSWORD_RESET_COUNT, STEP_TAG, "done");
  }

  void register(String username, String email) {
    log.debug("Registration requested for user: {}", username);
    validateUsername(username);
    emailService.validateEmailAddress(email);

    if (userRepository.existsByEmail(email)) {
      throw ApiException.of(ErrorCode.EMAIL_REGISTERED, email);
    }

    int usernameReservationTimeInMonths = properties.getUser().getUsernameReservationTimeInMonths();
    nameRecordRepository.getLastUsernameOwnerWithinMonths(username, usernameReservationTimeInMonths)
      .ifPresent(reservedByUserId -> {
        throw ApiException.of(ErrorCode.USERNAME_RESERVED, username, usernameReservationTimeInMonths);
      });

    String token = fafTokenService.createToken(FafTokenType.REGISTRATION,
      Duration.ofSeconds(properties.getRegistration().getLinkExpirationSeconds()),
      ImmutableMap.of(
        KEY_USERNAME, username,
        KEY_EMAIL, email
      ));

    String activationUrl = String.format(properties.getRegistration().getActivationUrlFormat(), username, token);

    emailService.sendActivationMail(username, email, activationUrl);


    userRegistrationCounter.increment();
  }

  private void validateUsername(String username) {
    if (!USERNAME_PATTERN.matcher(username).matches()) {
      throw ApiException.of(ErrorCode.USERNAME_INVALID, username);
    }
    if (userRepository.existsByLogin(username)) {
      throw ApiException.of(ErrorCode.USERNAME_TAKEN, username);
    }
  }

  /**
   * Creates a new user based on the information in the activation token.
   *
   * @param registrationToken the JWT in the format: <pre>
   *   {
   *     "action": "activate",
   *     "expiry": "2011-12-03T10:15:30Z",
   *     "
   *   }
   * </pre>
   */
  @Transactional
  void activate(String registrationToken, String password, String ipAddress) {
    Map<String, String> claims = fafTokenService.resolveToken(FafTokenType.REGISTRATION, registrationToken);

    String username = claims.get(KEY_USERNAME);
    String email = claims.get(KEY_EMAIL);

    // the username could have been taken in the meantime
    validateUsername(username);

    User user = new User();
    user.setPassword(passwordEncoder.encode(password));
    user.setEmail(email);
    user.setLogin(username);
    user.setRecentIpAddress(ipAddress);

    user = userRepository.save(user);

    // @Deprecated
    // TODO: Move this db activity to the server (upcert instead of update) */
    // >>>
    double mean = properties.getRating().getDefaultMean();
    double deviation = properties.getRating().getDefaultDeviation();

    globalRatingRepository.save((GlobalRating) new GlobalRating()
      .setId(user.getId())
      .setMean(mean)
      .setDeviation(deviation));

    ladder1v1RatingRepository.save((Ladder1v1Rating) new Ladder1v1Rating()
      .setId(user.getId())
      .setMean(mean)
      .setDeviation(deviation));
    // <<<

    log.debug("User has been activated: {}", user);

    broadcastUserChange(user);
    userActivationCounter.increment();
  }

  void changePassword(String currentPassword, String newPassword, User user) {
    if (!Objects.equals(user.getPassword(), passwordEncoder.encode(currentPassword))) {
      throw ApiException.of(ErrorCode.PASSWORD_CHANGE_FAILED_WRONG_PASSWORD);
    }

    setPassword(user, newPassword);
  }

  @Transactional
  public void changeLogin(String newLogin, User user, String ipAddress) {
    internalChangeLogin(newLogin, user, ipAddress, false);
  }

  @Transactional
  public void changeLoginForced(String newLogin, User user, String ipAddress) {
    internalChangeLogin(newLogin, user, ipAddress, true);
  }

  private void internalChangeLogin(String newLogin, User user, String ipAddress, boolean force) {
    validateUsername(newLogin);

    if (!force) {
      int minDaysBetweenChange = properties.getUser().getMinimumDaysBetweenUsernameChange();
      nameRecordRepository.getDaysSinceLastNewRecord(user.getId(), minDaysBetweenChange)
        .ifPresent(daysSinceLastRecord -> {
          throw ApiException.of(ErrorCode.USERNAME_CHANGE_TOO_EARLY, minDaysBetweenChange - daysSinceLastRecord.intValue() + 1);
        });

      int usernameReservationTimeInMonths = properties.getUser().getUsernameReservationTimeInMonths();
      nameRecordRepository.getLastUsernameOwnerWithinMonths(newLogin, usernameReservationTimeInMonths)
        .ifPresent(reservedByUserId -> {
          if (!reservedByUserId.equals(user.getId())) {
            throw ApiException.of(ErrorCode.USERNAME_RESERVED, newLogin, usernameReservationTimeInMonths);
          }
        });

    }
    log.debug("Changing username for user ''{}'' to ''{}'', forced:''{}''", user.getLogin(), newLogin, force);
    NameRecord nameRecord = new NameRecord()
      .setName(user.getLogin())
      .setPlayer(playerRepository.getOne(user.getId()));
    nameRecordRepository.save(nameRecord);

    user.setLogin(newLogin);
    user.setRecentIpAddress(ipAddress);

    userRepository.save(user);
    broadcastUserChange(user);
    userNameChangeCounter.increment();
  }

  public void changeEmail(String currentPassword, String newEmail, User user, String ipAddress) {
    if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
      throw ApiException.of(ErrorCode.EMAIL_CHANGE_FAILED_WRONG_PASSWORD);
    }

    emailService.validateEmailAddress(newEmail);

    log.debug("Changing email for user ''{}'' to ''{}''", user.getLogin(), newEmail);
    user.setEmail(newEmail);
    user.setRecentIpAddress(ipAddress);

    userRepository.save(user);
    broadcastUserChange(user);
  }

  private void broadcastUserChange(User user) {
    UserUpdatedEvent userUpdatedEvent = new UserUpdatedEvent(
      user,
      user.getId(),
      user.getLogin(),
      user.getEmail(),
      user.getRecentIpAddress()
    );

    eventPublisher.publishEvent(userUpdatedEvent);
  }

  void requestPasswordReset(String identifier) {
    log.debug("Password reset requested for user-identifier: {}", identifier);

    User user = userRepository.findOneByLogin(identifier)
      .orElseGet(() -> userRepository.findOneByEmail(identifier)
        .orElseThrow(() -> ApiException.of(ErrorCode.UNKNOWN_IDENTIFIER, identifier)));

    String token = fafTokenService.createToken(FafTokenType.PASSWORD_RESET,
      Duration.ofSeconds(properties.getRegistration().getLinkExpirationSeconds()),
      ImmutableMap.of(KEY_USER_ID, String.valueOf(user.getId())));

    String passwordResetUrl = String.format(properties.getPasswordReset().getPasswordResetUrlFormat(), user.getLogin(), token);

    emailService.sendPasswordResetMail(user.getLogin(), user.getEmail(), passwordResetUrl);
    userPasswordResetRequestCounter.increment();
  }

  void performPasswordReset(String token, String newPassword) {
    log.debug("Trying to reset password with token: {}", token);
    Map<String, String> claims = fafTokenService.resolveToken(FafTokenType.PASSWORD_RESET, token);

    int userId = Integer.parseInt(claims.get(KEY_USER_ID));
    User user = userRepository.findById(userId)
      .orElseThrow(() -> ApiException.of(TOKEN_INVALID));

    setPassword(user, newPassword);
    userPasswordResetDoneCounter.increment();
  }

  private void setPassword(User user, String password) {
    log.debug("Updating FAF password for user: {}", user.getLogin());
    user.setPassword(passwordEncoder.encode(password));
    userRepository.save(user);
    log.debug("Updating anope password for user: {}", user.getLogin());
    anopeUserRepository.updatePassword(user.getLogin(), Hashing.md5().hashString(password, StandardCharsets.UTF_8).toString());
  }

  public User getUser(Authentication authentication) {
    if (authentication != null
      && authentication.getPrincipal() != null
      && authentication.getPrincipal() instanceof FafUserDetails) {
      return getUser(((FafUserDetails) authentication.getPrincipal()).getId());
    }
    throw ApiException.of(TOKEN_INVALID);
  }

  public User getUser(int userId) {
    return userRepository.findById(userId).orElseThrow(() -> ApiException.of(TOKEN_INVALID));
  }

  public String buildSteamLinkUrl(User user, String callbackUrl) {
    log.debug("Building Steam link url for user id: {}", user.getId());
    if (user.getSteamId() != null && !Objects.equals(user.getSteamId(), "")) {
      log.debug("User with id '{}' already linked to steam", user.getId());
      throw ApiException.of(ErrorCode.STEAM_ID_UNCHANGEABLE);
    }

    String token = fafTokenService.createToken(FafTokenType.LINK_TO_STEAM,
      Duration.ofHours(1),
      ImmutableMap.of(
        KEY_USER_ID, String.valueOf(user.getId()),
        KEY_STEAM_LINK_CALLBACK_URL, callbackUrl
      )
    );

    userSteamLinkRequestedCounter.increment();
    return steamService.buildLoginUrl(String.format(properties.getLinkToSteam().getSteamRedirectUrlFormat(), token));
  }

  public SteamLinkResult linkToSteam(String token, String steamId) {
    log.debug("linkToSteam requested for steamId '{}' with token: {}", steamId, token);
    List<Error> errors = new ArrayList<>();
    Map<String, String> attributes = fafTokenService.resolveToken(FafTokenType.LINK_TO_STEAM, token);

    User user = userRepository.findById(Integer.parseInt(attributes.get(KEY_USER_ID)))
      .orElseThrow(() -> ApiException.of(TOKEN_INVALID));

    if (!steamService.ownsForgedAlliance(steamId)) {
      errors.add(new Error(ErrorCode.STEAM_LINK_NO_FA_GAME));
    }

    Optional<User> userWithSameSteamIdOptional = userRepository.findOneBySteamId(steamId);
    userWithSameSteamIdOptional.ifPresent(userWithSameId ->
      errors.add(new Error(ErrorCode.STEAM_ID_ALREADY_LINKED, userWithSameId.getLogin()))
    );

    if (errors.isEmpty()) {
      user.setSteamId(steamId);
      userRepository.save(user);
    }

    String callbackUrl = attributes.get(KEY_STEAM_LINK_CALLBACK_URL);
    userSteamLinkDoneCounter.increment();
    return new SteamLinkResult(callbackUrl, errors);
  }

  @Value
  static class SteamLinkResult {
    String callbackUrl;
    List<Error> errors;
  }
}
