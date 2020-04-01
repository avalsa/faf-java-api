package com.faforever.api.dto;


import com.github.jasminb.jsonapi.models.errors.Error;
import lombok.Value;

import java.util.List;
import java.util.stream.Collectors;

@Value
public class ApiError {

  private List<Error> errors;

  public String getLocalizedMessage() {
    // TODO localize
    return errors.stream()
      .map(Error::getDetail)
      .collect(Collectors.joining("\n"));
  }
}
