#!/bin/sh

curl -s https://raw.githubusercontent.com/PodVinci/podvinci.github.io/master/_config.yml | yq -r ".languages[]" > ./app/src/main/assets/website-languages.txt
python ./createContributors.py
