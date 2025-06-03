package com.example.springdemo.mapper;

import com.example.springdemo.dto.PersonRequestDTO;
import com.example.springdemo.dto.PersonResponseDTO;
import com.example.springdemo.model.Person;
import org.mapstruct.*;


@Mapper(componentModel = "spring")
public interface PersonMapper {

    @Mapping(target = "parent", ignore = true)
    @Mapping(target = "children", ignore = true)
    Person toEntity(PersonRequestDTO dto);
    
    @Mapping(target = "parent", source = "parent")
    PersonResponseDTO toDto(Person entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "parent", ignore = true)
    @Mapping(target = "children", ignore = true)
    void updateEntityFromDto(PersonRequestDTO dto, @MappingTarget Person entity);

    // Map parent to ParentDTO
    @Mapping(target = "firstName", source = "firstName")
    @Mapping(target = "lastName", source = "lastName")
    @Mapping(target = "id", source = "id")
    PersonResponseDTO toParentDto(Person parent);


    PersonResponseDTO toDtoWithParent(Person entity);
    

} 