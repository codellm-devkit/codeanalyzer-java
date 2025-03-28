/*
 * This source file was generated by the Gradle 'init' task
 */
package org.example;

public class App {
    public static void main(String[] args) {
        // Create instances of records
        PersonRecord person = new PersonRecord("Alice", 30);
        CarRecord car = new CarRecord("Tesla Model 3", 2023);
        // Access public fields and methods
        System.out.println(person.greet());
        System.out.println(car.getCarDetails());

        // Access package-private method (allowed within the same package)
        System.out.println("Person Internal Info: " + person.internalInfo());
        System.out.println("Car Internal VIN: " + car.getInternalVIN());
    }
}
