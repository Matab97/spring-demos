package com.example.springdemo.controller;

import com.example.springdemo.dto.PersonRequestDTO;
import com.example.springdemo.dto.PersonResponseDTO;
import com.example.springdemo.service.PersonService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/persons")
@RequiredArgsConstructor
public class PersonController {

    private final PersonService personService;

    @GetMapping
    public List<PersonResponseDTO> getAllPersons() {
        return personService.getAllPersons();
    }

    @GetMapping("/{id}")
    public PersonResponseDTO getPersonById(@PathVariable Long id) {
        return personService.getPersonById(id);
    }

    @PostMapping
    public PersonResponseDTO createPerson(@Valid @RequestBody PersonRequestDTO personDto) {
        return personService.createPerson(personDto);
    }

    @PutMapping("/{id}")
    public PersonResponseDTO updatePerson(@PathVariable Long id, @Valid @RequestBody PersonRequestDTO personDto) {
        return personService.updatePerson(id, personDto);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deletePerson(@PathVariable Long id) {
        personService.deletePerson(id);
        return ResponseEntity.ok().build();
    }
} 