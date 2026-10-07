#!/usr/bin/env bash
# XhlCLI Official Quick Installer
# https://github.com/sz-xiaohuolong/XhlCli

set -euo pipefail

XHLCLI_VERSION="1.0.1"
INSTALL_DIR="${HOME}/.xhlcli"
BIN_DIR="${INSTALL_DIR}/bin"
JAR_NAME="xhlcli.jar"
REPO_URL="https://github.com/sz-xiaohuolong/XhlCli"

# Styling
BOLD="\033[1m"
GREEN="\033[32m"
ORANGE="\033[38;5;208m"
RED="\033[31m"
BLUE="\033[34m"
RESET="\033[0m"

echo -e "${ORANGE}"
cat << 'EOF'
 __   __ _     _  ____ _     ___ 
 \ \ / /| |__ | |/ ___| |   |_ _|
  \ V / | '_ \| | |   | |    | | 
   | |  | | | | | |___| |___ | | 
   |_|  |_| |_|_|\____|_____|___|
EOF
echo -e "${RESET}"
echo -e "${BOLD}XhlCLI Installer v${XHLCLI_VERSION}${RESET}"
echo "Autonomous Programming Agent CLI for Demanding Engineers"
echo "---------------------------------------------------------"

# 1. Detect OS & Architecture
OS="$(uname -s)"
ARCH="$(uname -m)"

case "${OS}" in
    Darwin)
        PLATFORM="macOS"
        ;;
    Linux)
        PLATFORM="Linux"
        ;;
    *)
        echo -e "${RED}Error: Unsupported operating system: ${OS}.${RESET}"
        echo "Please build from source using ./mvnw clean package"
        exit 1
        ;;
esac

echo -e "Platform detected: ${BLUE}${PLATFORM} (${ARCH})${RESET}"

# 2. Check Java Runtime Environment (Java 21+ required)
echo -n "Checking Java 21+ runtime... "
if command -v java >/dev/null 2>&1; then
    JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
    if [ -n "${JAVA_VER}" ] && [ "${JAVA_VER}" -ge 21 ]; then
        echo -e "${GREEN}Found Java ${JAVA_VER}!${RESET}"
    else
        echo -e "${RED}Java ${JAVA_VER} detected. Java 21 or higher is required.${RESET}"
        echo -e "Please install OpenJDK 21 via:"
        echo -e "  macOS:  ${BOLD}brew install openjdk@21${RESET}"
        echo -e "  Ubuntu: ${BOLD}sudo apt install openjdk-21-jdk${RESET}"
        echo -e "  SDKMAN: ${BOLD}sdk install java 21.0.2-tem${RESET}"
        exit 1
    fi
else
    echo -e "${RED}Not found.${RESET}"
    echo -e "Java 21 or higher is required. Please install OpenJDK 21 first."
    exit 1
fi

# 3. Create target directory
mkdir -p "${BIN_DIR}"

# 4. Check if local fat jar exists in repository, or download from release
if [ -f "./target/xhlcli-${XHLCLI_VERSION}.jar" ]; then
    echo "Using locally built JAR: ./target/xhlcli-${XHLCLI_VERSION}.jar"
    cp "./target/xhlcli-${XHLCLI_VERSION}.jar" "${INSTALL_DIR}/${JAR_NAME}"
elif [ -f "./target/xhlcli.jar" ]; then
    echo "Using locally built JAR: ./target/xhlcli.jar"
    cp "./target/xhlcli.jar" "${INSTALL_DIR}/${JAR_NAME}"
else
    echo "Downloading XhlCLI v${XHLCLI_VERSION} binary..."
    DOWNLOAD_URL="${REPO_URL}/releases/download/v${XHLCLI_VERSION}/xhlcli-${XHLCLI_VERSION}.jar"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "${DOWNLOAD_URL}" -o "${INSTALL_DIR}/${JAR_NAME}" || {
            echo -e "${ORANGE}Release binary not yet published online. Building locally from source...${RESET}"
            if [ -f "./mvnw" ]; then
                ./mvnw clean package -DskipTests -q
                cp target/*.jar "${INSTALL_DIR}/${JAR_NAME}"
            else
                echo -e "${RED}Unable to build without ./mvnw. Please clone repository first.${RESET}"
                exit 1
            fi
        }
    fi
fi

# 5. Generate executable launcher script
cat << EOF > "${BIN_DIR}/xhlcli"
#!/usr/bin/env bash
set -e
exec java -jar "${INSTALL_DIR}/${JAR_NAME}" "\$@"
EOF

chmod +x "${BIN_DIR}/xhlcli"

echo ""
echo -e "${GREEN}✓ Successfully installed XhlCLI to ${BIN_DIR}/xhlcli${RESET}"
echo ""

# 6. Check PATH
case ":${PATH}:" in
    *":${BIN_DIR}:"*)
        ;;
    *)
        echo -e "${BOLD}Please add XhlCLI to your PATH:${RESET}"
        echo ""
        if [ -f "${HOME}/.zshrc" ]; then
            echo -e "  echo 'export PATH=\"${BIN_DIR}:\$PATH\"' >> ~/.zshrc"
            echo -e "  source ~/.zshrc"
        elif [ -f "${HOME}/.bashrc" ]; then
            echo -e "  echo 'export PATH=\"${BIN_DIR}:\$PATH\"' >> ~/.bashrc"
            echo -e "  source ~/.bashrc"
        else
            echo -e "  export PATH=\"${BIN_DIR}:\$PATH\""
        fi
        echo ""
        ;;
esac

echo -e "${BOLD}Configure your API Key (choose one):${RESET}"
echo -e "  Global config: ${GREEN}echo '{\"apiKey\":\"your_api_key\"}' > ~/.xhlcli/config.json && chmod 600 ~/.xhlcli/config.json${RESET}"
echo -e "  Environment:   ${GREEN}export DEEPSEEK_API_KEY=\"your_api_key\"${RESET}"
echo ""
echo -e "Run ${ORANGE}${BOLD}xhlcli${RESET} to start your first session!"
