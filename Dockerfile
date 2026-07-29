FROM ubuntu:22.04

# Avoid interactive prompts
ENV DEBIAN_FRONTEND=noninteractive

# Install Java 17 + OpenSSH
RUN apt-get update && \
    apt-get install -y openjdk-17-jre-headless openssh-server sudo sqlite3 && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Create SSH directory and set up sshd
RUN mkdir -p /var/run/sshd && \
    mkdir -p /root/.ssh && \
    chmod 700 /root/.ssh

# Create crawler user
RUN useradd -m -s /bin/bash crawler && \
    mkdir -p /home/crawler/.ssh && \
    chmod 700 /home/crawler/.ssh && \
    mkdir -p /home/crawler/logs

# SSH config: disable password login, enable key auth
RUN echo "PasswordAuthentication no" >> /etc/ssh/sshd_config && \
    echo "PubkeyAuthentication yes" >> /etc/ssh/sshd_config && \
    echo "PermitRootLogin prohibit-password" >> /etc/ssh/sshd_config

# Expose SSH port
EXPOSE 22

# Start SSH daemon
CMD ["/usr/sbin/sshd", "-D"]
