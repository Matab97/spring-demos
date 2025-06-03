package com.example.springdemo.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @Email(message = "Please provide a valid email address")
    private String email;

    @NotNull(message = "Date of birth is required")
    private LocalDate dateOfBirth;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "parent_id")
    private Person parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL)
    private Set<Person> children = new HashSet<>();

    // Helper method to maintain bidirectional relationship
    public void addChild(Person child) {
        children.add(child);
        child.setParent(this);
    }

    // Helper method to remove child
    public void removeChild(Person child) {
        children.remove(child);
        child.setParent(null);
    }
} 