public class LPG {
    private String lpgId, brand;
    private double price, weight;

    public LPG(String lpgId,String brand, double price,double weight) {
        this.lpgId = lpgId;
        this.price = price;
        this.brand = brand;
        this.weight = weight;
    }

    public String getLpgId() { return lpgId; }
    public double getPrice() { return price; }
    public String getBrand() { return brand; }
    public double getWeight() { return weight; }

    @Override
    public String toString() {
        return lpgId + " - " + brand + " (" + weight + "kg) - ₱" + price;
    }
}
