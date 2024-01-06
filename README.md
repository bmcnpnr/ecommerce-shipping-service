# ecommerce-shipping-service
I will develop an e-commerce website backend (project is suggested by ChatGPT) to develop my azure, microservices and kubernetes experience
docker build -t shipping-service:0.0.1-SNAPSHOT -f docker/Dockerfile .
docker tag shipping-service:0.0.1-SNAPSHOT bmcnpnr/ecommerce-shipping-service:latest
docker push bmcnpnr/ecommerce-shipping-service:latest
