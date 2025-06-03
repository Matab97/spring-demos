package com.example.springdemo.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;

@Getter
@Setter
public class AuthRequestDTO {
    
    @NotBlank
    private String username;
    
    @NotBlank
    private String password;
} 