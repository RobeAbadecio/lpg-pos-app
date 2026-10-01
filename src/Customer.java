public class Customer {
    private String custId, firstName, lastName, contactNo, address;

    public Customer(String custId, String firstName, String lastName, String contactNo, String address) {
        this.custId = custId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.contactNo = contactNo;
        this.address = address;
    }

    public String getCustId() { return custId; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getContactNo() { return contactNo; }
    public String getAddress() { return address; }

    @Override
    public String toString() {
        return custId + " - " + firstName + " " + lastName;
    }
}