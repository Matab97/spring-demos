package com.example.springdemo.service;

import com.example.springdemo.dto.PersonRequestDTO;
import com.example.springdemo.dto.PersonResponseDTO;
import com.example.springdemo.mapper.PersonMapper;
import com.example.springdemo.model.Person;
import com.example.springdemo.repository.PersonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityNotFoundException;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class PersonService {

    private final PersonRepository personRepository;
    private final PersonMapper personMapper;

    @Transactional(readOnly = true)
    public List<PersonResponseDTO> getAllPersons() {
        return personRepository.findAll().stream()
                .map(personMapper::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PersonResponseDTO getPersonById(Long id) {
        Person person = personRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Person not found with id: " + id));
        return personMapper.toDto(person);
    }

    public PersonResponseDTO createPerson(PersonRequestDTO personDto) {
        Person person = personMapper.toEntity(personDto);
        
        // Set parent if parentId is provided
        if (personDto.getParentId() != null) {
            Person parent = personRepository.findById(personDto.getParentId())
                    .orElseThrow(() -> new EntityNotFoundException("Parent not found with id: " + personDto.getParentId()));
            parent.addChild(person);
        }
        
        Person savedPerson = personRepository.save(person);
        return personMapper.toDto(savedPerson);
    }

    public PersonResponseDTO updatePerson(Long id, PersonRequestDTO personDto) {
        Person person = personRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Person not found with id: " + id));
        
        personMapper.updateEntityFromDto(personDto, person);
        
        // Update parent if parentId has changed
        if (personDto.getParentId() != null && 
            (person.getParent() == null || !person.getParent().getId().equals(personDto.getParentId()))) {
            
            // Remove from old parent if exists
            if (person.getParent() != null) {
                person.getParent().removeChild(person);
            }
            
            // Set new parent
            Person newParent = personRepository.findById(personDto.getParentId())
                    .orElseThrow(() -> new EntityNotFoundException("Parent not found with id: " + personDto.getParentId()));
            newParent.addChild(person);
        } else if (personDto.getParentId() == null && person.getParent() != null) {
            // Remove parent if parentId is null
            person.getParent().removeChild(person);
        }
        
        Person updatedPerson = personRepository.save(person);
        return personMapper.toDto(updatedPerson);
    }

    public void deletePerson(Long id) {
        Person person = personRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Person not found with id: " + id));
        
        // Remove from parent if exists
        if (person.getParent() != null) {
            person.getParent().removeChild(person);
        }
        
        personRepository.delete(person);
    }
} 